package com.flowdesk.auth.service;

import com.flowdesk.auth.domain.bo.AuthLoginBO;
import com.flowdesk.auth.domain.bo.AuthLoginResultBO;
import com.flowdesk.auth.domain.bo.AuthTokenPairBO;
import com.flowdesk.auth.domain.vo.AuthVO;
import com.flowdesk.auth.service.impl.AuthLoginServiceImpl;
import com.flowdesk.iam.domain.IamUserStatus;
import com.flowdesk.iam.domain.bo.IamAuthenticationBO;
import com.flowdesk.iam.service.IamUserService;
import com.flowdesk.shared.exception.ApiException;
import com.flowdesk.shared.utils.JwtUtils;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.LinkedHashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthLoginServiceImplTest {

    private final IamUserService iamUserService = mock(IamUserService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final TokenService tokenService = mock(TokenService.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private final AuthLoginServiceImpl service =
            new AuthLoginServiceImpl(iamUserService, passwordEncoder, tokenService, jwtUtils);

    @Test
    void loginReturnsAccessTokenAndSafeIdentity() {
        IamAuthenticationBO identity = enabledIdentity();
        when(iamUserService.getAuthenticationByUsername("alice")).thenReturn(identity);
        when(passwordEncoder.matches("correct-password", "argon2-hash")).thenReturn(true);
        when(tokenService.createLoginTokens(
                42L,
                "alice",
                java.util.List.of("EMPLOYEE"),
                java.util.List.of("TICKET_CREATE", "TICKET_VIEW_OWN")
        )).thenReturn(new AuthTokenPairBO(
                "signed-access-token", "raw-refresh-token", "session-id"));
        when(jwtUtils.getExpirationSeconds()).thenReturn(900L);

        AuthVO request = request("  alice  ", "correct-password");
        AuthLoginResultBO loginResult = service.login(request);
        AuthLoginBO result = loginResult.response();

        assertThat(result.accessToken()).isEqualTo("signed-access-token");
        assertThat(result.tokenType()).isEqualTo("Bearer");
        assertThat(result.expiresIn()).isEqualTo(900L);
        assertThat(result.user().id()).isEqualTo(42L);
        assertThat(result.user().roles()).containsExactly("EMPLOYEE");
        assertThat(result.user().permissions())
                .containsExactly("TICKET_CREATE", "TICKET_VIEW_OWN");
        assertThat(loginResult.refreshToken()).isEqualTo("raw-refresh-token");
        verify(iamUserService).getAuthenticationByUsername("alice");
    }

    @Test
    void missingUserWrongPasswordAndDisabledUserShareOnePublicError() {
        assertInvalidCredentials(null, false);

        IamAuthenticationBO enabled = enabledIdentity();
        assertInvalidCredentials(enabled, false);

        IamAuthenticationBO disabled = enabledIdentity();
        disabled.setStatus(IamUserStatus.DISABLED);
        assertInvalidCredentials(disabled, true);
    }

    private void assertInvalidCredentials(IamAuthenticationBO identity, boolean passwordMatches) {
        when(iamUserService.getAuthenticationByUsername("alice")).thenReturn(identity);
        if (identity != null && identity.getStatus() == IamUserStatus.ENABLED) {
            when(passwordEncoder.matches("wrong-password", "argon2-hash"))
                    .thenReturn(passwordMatches);
        }

        assertThatThrownBy(() -> service.login(request("alice", "wrong-password")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status().value()).isEqualTo(401);
                    assertThat(exception.code()).isEqualTo("AUTH_INVALID_CREDENTIALS");
                });
    }

    private IamAuthenticationBO enabledIdentity() {
        IamAuthenticationBO identity = new IamAuthenticationBO();
        identity.setId(42L);
        identity.setUsername("alice");
        identity.setDisplayName("Alice");
        identity.setPasswordHash("argon2-hash");
        identity.setStatus(IamUserStatus.ENABLED);
        identity.setRoleCodes(new LinkedHashSet<>(java.util.List.of("EMPLOYEE")));
        identity.setPermissionCodes(new LinkedHashSet<>(
                java.util.List.of("TICKET_VIEW_OWN", "TICKET_CREATE")));
        return identity;
    }

    private AuthVO request(String username, String password) {
        AuthVO request = new AuthVO();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }
}
