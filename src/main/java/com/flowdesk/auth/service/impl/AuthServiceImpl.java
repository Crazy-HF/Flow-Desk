package com.flowdesk.auth.service.impl;

import com.flowdesk.auth.config.AuthProperties;
import com.flowdesk.auth.config.JwtProperties;
import com.flowdesk.auth.domain.AccessToken;
import com.flowdesk.auth.domain.AuthSession;
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
    private final AuthProperties authProperties;
    private final JwtProperties jwtProperties;
    private final JwtTokenService jwtTokenService;
    private final AuthSessionRepository authSessionRepository;
    private final Clock clock;

    public AuthServiceImpl(IamAuthService iamAuthService,
                           PasswordEncoder passwordEncoder,
                           AuthProperties authProperties,
                           JwtProperties jwtProperties,
                           JwtTokenService jwtTokenService,
                           AuthSessionRepository authSessionRepository,
                           Clock clock) {
        this.iamAuthService = iamAuthService;
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

        AccessToken accessToken = jwtTokenService.issue(authentication.userId(), sessionId);

        AuthUserBO user = new AuthUserBO(authentication.userId(), authentication.username(),
                authentication.displayName(), authentication.roleCodes(), authentication.permissionCodes());
        AuthLoginBO response = new AuthLoginBO(accessToken.value(), AuthLoginBO.BEARER,
                jwtProperties.expiration().toSeconds(), user);

        return new AuthServiceBO(response, refreshToken);
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
}
