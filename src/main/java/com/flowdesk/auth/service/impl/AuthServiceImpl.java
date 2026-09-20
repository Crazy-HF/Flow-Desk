package com.flowdesk.auth.service.impl;

import com.flowdesk.auth.config.AuthProperties;
import com.flowdesk.auth.config.JwtProperties;
import com.flowdesk.auth.domain.AccessToken;
import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.domain.AuthSession;
import com.flowdesk.auth.domain.RefreshTokenLookup;
import com.flowdesk.auth.domain.bo.AuthLoginBO;
import com.flowdesk.auth.domain.bo.AuthServiceBO;
import com.flowdesk.auth.domain.bo.AuthUserBO;
import com.flowdesk.auth.domain.vo.AuthLoginVO;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.auth.security.JwtTokenService;
import com.flowdesk.auth.security.RefreshTokenUtils;
import com.flowdesk.auth.service.AuthService;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.iam.domain.IamUserStatus;
import com.flowdesk.iam.domain.bo.IamAuthBO;
import com.flowdesk.iam.service.IamAuthService;
import com.flowdesk.iam.service.IamUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {

    private final IamAuthService iamAuthService;
    private final PasswordEncoder passwordEncoder;
    private final IamUserService iamUserService;
    private final AuthProperties authProperties;
    private final JwtProperties jwtProperties;
    private final JwtTokenService jwtTokenService;
    private final AuthSessionRepository authSessionRepository;
    private final Clock clock;

    public AuthServiceImpl(IamAuthService iamAuthService,
                           PasswordEncoder passwordEncoder,
                           IamUserService iamUserService,
                           AuthProperties authProperties,
                           JwtProperties jwtProperties,
                           JwtTokenService jwtTokenService,
                           AuthSessionRepository authSessionRepository,
                           Clock clock) {
        this.iamAuthService = iamAuthService;
        this.iamUserService = iamUserService;
        this.passwordEncoder = passwordEncoder;
        this.authProperties = authProperties;
        this.jwtProperties = jwtProperties;
        this.jwtTokenService = jwtTokenService;
        this.authSessionRepository = authSessionRepository;
        this.clock = clock;
    }

    /**
     * 登录：认证通过后建立会话并签发访问令牌。
     *
     * <p>会话先落 Redis、再签发令牌：写入失败时直接抛出，避免把指向不存在会话的令牌发给客户端。</p>
     */
    @Override
    public AuthServiceBO login(AuthLoginVO authLoginVO) {
        // 认证
        IamAuthBO authentication = authenticate(authLoginVO);

        String sessionId = UUID.randomUUID().toString();
        String refreshToken = RefreshTokenUtils.generate(authProperties.refreshTokenBytes());

        Instant now = clock.instant();
        AuthSession session = new AuthSession(
                sessionId,
                authentication.userId(),
                authentication.username(),
                authentication.displayName(),
                RefreshTokenUtils.digest(refreshToken),
                authentication.roleCodes(),
                authentication.permissionCodes(),
                now,
                now.plus(authProperties.refreshExpiration()));

        authSessionRepository.save(session);

        return issue(session, refreshToken);
    }

    /** 认证：用户不存在、账号停用、密码错误共用同一个出口。 */
    IamAuthBO authenticate(AuthLoginVO authLoginVO) {
        IamAuthBO authentication = iamAuthService.findByUsername(authLoginVO.username());

        if (authentication == null
                || authentication.status() != IamUserStatus.ENABLED
                || !passwordEncoder.matches(authLoginVO.password(), authentication.password())) {
            log.warn("SECURITY login-failed username={} result=AUTH_INVALID_CREDENTIALS", authLoginVO.username());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "登录名或密码不正确");
        }
        return authentication;
    }

    /**
     * 刷新令牌：验证刷新令牌的签名、有效期、绑定关系，成功则签发新的访问令牌。
     * @param refreshToken 来自 HttpOnly Cookie 的原始令牌；缺失时同样按失败处理
     * @return
     */
    @Override
    public AuthServiceBO refresh(String refreshToken) {
        if(refreshToken  ==null || refreshToken.isBlank())
            throw sessionInvalid();

        //1.查看当前令牌：refreshToken状态
        RefreshTokenLookup lookup = authSessionRepository.findByRefreshDigest(RefreshTokenUtils.digest(refreshToken));
        //REUSED:已出现的票重新出现，说明该票已经丢失
        if(lookup.status() == RefreshTokenLookup.Status.REUSED){
            log.warn("SECURITY refresh-token-reused sessionId={}", lookup.sessionId());
            // 撤销会话
            authSessionRepository.revoke(lookup.sessionId());
            throw sessionInvalid();
        }
        //UNKNOWN:未知的票，说明该票从未出现过
        if(lookup.status() == RefreshTokenLookup.Status.UNKNOWN)
            throw sessionInvalid();

        //2.refreshToken状态活跃，只代表记录存在，会话本身可能已经到期
        AuthSession session = requireActiveSession(lookup.sessionId());

        //3.轮换，生成新的refresh Token
        String newRefreshToken = RefreshTokenUtils.generate(authProperties.refreshTokenBytes());
        //更新会话
        AuthSession roated = session.withRefreshDigest(RefreshTokenUtils.digest(newRefreshToken));
        authSessionRepository.rotate(roated,session.refreshDigest());

        //4.同一个会话签发新的Access Token
        return issue(roated, newRefreshToken);
    }

    /** 签发 Access Token 并组装响应：登录与刷新共用同一响应形状。 */
    private AuthServiceBO issue(AuthSession session, String refreshToken) {
        // 签发 Access Token
        AccessToken accessToken = jwtTokenService.issue(session.userId(), session.sessionId());
        // token传递到前端Js
        AuthUserBO user = new AuthUserBO(session.userId(), session.username(), session.displayName(),
                session.roleCodes(), session.permissionCodes());
        // Refresh 只进cookie
        AuthLoginBO response = new AuthLoginBO(accessToken.value(), AuthLoginBO.BEARER,
                jwtProperties.expiration().toSeconds(), user);
        // 组装响应
        return new AuthServiceBO(response, refreshToken);
    }

    /** 刷新链路的统一失败出口：缺失、无效、过期、已撤销、被重放对外同义。 */
    private ApiException sessionInvalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_SESSION_INVALID", "登录会话已失效，请重新登录");
    }

    /**
     * 退出：幂等。没有 Cookie、令牌无效、会话已不存在都算成功，也不区分会话是否曾经存在。
     */
    @Override
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        RefreshTokenLookup lookup = authSessionRepository.findByRefreshDigest(RefreshTokenUtils.digest(refreshToken));
        if (lookup.status() == RefreshTokenLookup.Status.UNKNOWN) {
            return;
        }
        // ACTIVE 是正常退出；REUSED 说明这张票被换过，但"结束这个会话"的意图仍然是明确的
        authSessionRepository.revoke(lookup.sessionId());
    }

    @Override
    public AuthUserBO currentUser(AuthPrincipal principal) {
        AuthSession session = requireActiveSession(principal.sessionId());
        return new AuthUserBO(session.userId(), session.username(), session.displayName(),
                session.roleCodes(), session.permissionCodes());
    }

    /** 取仍然有效的会话快照：不存在或已过期都按会话失效处理（防止 TTL 与快照过期时间不一致）。 */
    private AuthSession requireActiveSession(String sessionId) {
        AuthSession session = authSessionRepository.findById(sessionId).orElseThrow(this::sessionInvalid);
        if (!session.expiresAt().isAfter(clock.instant())) {
            throw sessionInvalid();
        }
        return session;
    }

    /**
     * 修改密码：先认证再修改。
     * @param principal
     * @param currentPassword
     * @param newPassword
     */
    @Override
    public void changePassword(AuthPrincipal principal, String currentPassword, String newPassword) {
        // 1. 与登录同一口径校验原密码：用户不存在、账号停用、密码错误都算失败
        IamAuthBO authentication = iamAuthService.findByUsername(principal.username());
        if (authentication == null
                || authentication.status() != IamUserStatus.ENABLED
                || !passwordEncoder.matches(currentPassword, authentication.password())) {
            log.warn("SECURITY change-password-failed userId={} result=AUTH_INVALID_CREDENTIALS", principal.userId());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "当前密码不正确");
        }

        // 2. 先撤销该用户全部会话（含当前会话），再写入新密码
        authSessionRepository.revokeAll(principal.userId());

        // 3. 版本冲突说明同时有别的修改；此时会话已撤销，用户需要用原密码重新登录
        if (!iamUserService.updatePassword(principal.userId(), passwordEncoder.encode(newPassword)))
            throw new ApiException(HttpStatus.CONFLICT, "USER_CONFLICT", "用户信息已变化，请重新登录后再试");

    }
}
