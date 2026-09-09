package com.flowdesk.auth.service.impl;

import com.flowdesk.auth.domain.bo.AuthLoginBO;
import com.flowdesk.auth.domain.bo.AuthLoginResultBO;
import com.flowdesk.auth.domain.bo.AuthTokenPairBO;
import com.flowdesk.auth.domain.bo.AuthUserBO;
import com.flowdesk.auth.domain.vo.AuthVO;
import com.flowdesk.auth.service.AuthLoginService;
import com.flowdesk.auth.service.TokenService;
import com.flowdesk.iam.domain.IamUserStatus;
import com.flowdesk.iam.domain.bo.IamAuthenticationBO;
import com.flowdesk.iam.service.IamUserService;
import com.flowdesk.shared.exception.ApiException;
import com.flowdesk.shared.utils.JwtUtils;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Service
public class AuthLoginServiceImpl implements AuthLoginService {

    private final IamUserService iamUserService;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final JwtUtils jwtUtils;

    public AuthLoginServiceImpl(
            IamUserService iamUserService,
            PasswordEncoder passwordEncoder,
            TokenService tokenService,
            JwtUtils jwtUtils
    ) {
        this.iamUserService = iamUserService;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.jwtUtils = jwtUtils;
    }

    /** 登录失败使用同一状态和错误码，避免泄露账号是否存在或停用。 */
    @Override
    public AuthLoginResultBO login(AuthVO authVO) {
        Objects.requireNonNull(authVO, "登录参数不能为 null");

        String username = authVO.getUsername() == null ? "" : authVO.getUsername().trim();
        if (username.isEmpty() || authVO.getPassword() == null) {
            throw invalidCredentials();
        }

        IamAuthenticationBO identity = iamUserService.getAuthenticationByUsername(username);
        boolean credentialsValid = identity != null
                && identity.getStatus() == IamUserStatus.ENABLED
                && identity.getPasswordHash() != null
                && passwordEncoder.matches(authVO.getPassword(), identity.getPasswordHash());
        if (!credentialsValid) {
            throw invalidCredentials();
        }

        List<String> roles = normalizedCodes(identity.getRoleCodes());
        List<String> permissions = normalizedCodes(identity.getPermissionCodes());
        AuthTokenPairBO tokens = tokenService.createLoginTokens(
                identity.getId(), identity.getUsername(), roles, permissions);

        AuthUserBO user = new AuthUserBO(
                identity.getId(),
                identity.getUsername(),
                identity.getDisplayName(),
                roles,
                permissions
        );
        AuthLoginBO response = new AuthLoginBO(
                tokens.accessToken(), jwtUtils.getExpirationSeconds(), user);
        return new AuthLoginResultBO(response, tokens.refreshToken());
    }

    private List<String> normalizedCodes(Iterable<String> codes) {
        if (codes == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        codes.forEach(code -> {
            if (code != null && !code.isBlank()) {
                result.add(code);
            }
        });
        return result.stream().distinct().sorted().toList();
    }

    private ApiException invalidCredentials() {
        return new ApiException(
                HttpStatus.UNAUTHORIZED,
                "AUTH_INVALID_CREDENTIALS",
                "登录名或密码不正确"
        );
    }
}
