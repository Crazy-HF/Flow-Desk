package com.flowdesk.auth.service;

import com.flowdesk.auth.config.AuthProperties;
import com.flowdesk.auth.domain.AuthSession;
import com.flowdesk.auth.domain.bo.AuthTokenPairBO;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.auth.service.impl.TokenServiceImpl;
import com.flowdesk.shared.utils.JwtUtils;
import com.flowdesk.shared.utils.RefreshTokenUtils;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenServiceImplTest {

    @Test
    void createsSessionWithRefreshDigestAndSessionBoundJwt() {
        AuthSessionRepository repository = mock(AuthSessionRepository.class);
        JwtUtils jwtUtils = mock(JwtUtils.class);
        RefreshTokenUtils refreshTokenUtils = mock(RefreshTokenUtils.class);
        AuthProperties properties = new AuthProperties();
        properties.setRefreshExpiration(Duration.ofDays(7));
        properties.setRefreshTokenBytes(32);
        Instant now = Instant.parse("2026-09-11T06:00:00Z");
        TokenServiceImpl service = new TokenServiceImpl(
                repository,
                jwtUtils,
                refreshTokenUtils,
                properties,
                Clock.fixed(now, ZoneOffset.UTC)
        );
        when(refreshTokenUtils.generate(32)).thenReturn("raw-refresh-token");
        when(refreshTokenUtils.digest("raw-refresh-token")).thenReturn("refresh-digest");
        when(jwtUtils.generateAccessToken(
                org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("alice"),
                org.mockito.ArgumentMatchers.anyString()
        )).thenReturn("signed-access-token");

        AuthTokenPairBO result = service.createLoginTokens(
                42L, "alice", List.of("EMPLOYEE"), List.of("TICKET_CREATE"));

        assertThat(result.accessToken()).isEqualTo("signed-access-token");
        assertThat(result.refreshToken()).isEqualTo("raw-refresh-token");
        assertThat(result.sessionId()).isNotBlank();

        ArgumentCaptor<AuthSession> sessionCaptor = ArgumentCaptor.forClass(AuthSession.class);
        verify(repository).create(sessionCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(Duration.ofDays(7)));
        AuthSession stored = sessionCaptor.getValue();
        assertThat(stored.sessionId()).isEqualTo(result.sessionId());
        assertThat(stored.userId()).isEqualTo(42L);
        assertThat(stored.refreshTokenDigest()).isEqualTo("refresh-digest");
        assertThat(stored.refreshTokenDigest()).doesNotContain("raw-refresh-token");
        assertThat(stored.roles()).containsExactly("EMPLOYEE");
        assertThat(stored.permissions()).containsExactly("TICKET_CREATE");
        assertThat(stored.createdAt()).isEqualTo(now);
        assertThat(stored.expiresAt()).isEqualTo(now.plus(Duration.ofDays(7)));
    }
}
