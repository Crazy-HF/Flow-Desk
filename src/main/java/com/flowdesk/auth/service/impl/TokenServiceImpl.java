package com.flowdesk.auth.service.impl;

import com.flowdesk.auth.config.AuthProperties;
import com.flowdesk.auth.domain.AuthSession;
import com.flowdesk.auth.domain.bo.AuthTokenPairBO;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.auth.service.TokenService;
import com.flowdesk.shared.utils.JwtUtils;
import com.flowdesk.shared.utils.RefreshTokenUtils;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class TokenServiceImpl implements TokenService {

    private final AuthSessionRepository sessionRepository;
    private final JwtUtils jwtUtils;
    private final RefreshTokenUtils refreshTokenUtils;
    private final AuthProperties properties;
    private final Clock clock;

    public TokenServiceImpl(
            AuthSessionRepository sessionRepository,
            JwtUtils jwtUtils,
            RefreshTokenUtils refreshTokenUtils,
            AuthProperties properties,
            Clock clock
    ) {
        this.sessionRepository = sessionRepository;
        this.jwtUtils = jwtUtils;
        this.refreshTokenUtils = refreshTokenUtils;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public AuthTokenPairBO createLoginTokens(
            Long userId,
            String username,
            Collection<String> roles,
            Collection<String> permissions
    ) {
        String sessionId = UUID.randomUUID().toString();
        String refreshToken = refreshTokenUtils.generate(properties.getRefreshTokenBytes());
        String refreshDigest = refreshTokenUtils.digest(refreshToken);
        List<String> roleSnapshot = roles == null ? List.of() : List.copyOf(roles);
        List<String> permissionSnapshot = permissions == null ? List.of() : List.copyOf(permissions);
        String accessToken = jwtUtils.generateAccessToken(userId, username, sessionId);

        Duration ttl = properties.getRefreshExpiration();
        Instant now = clock.instant();
        sessionRepository.create(
                new AuthSession(
                        sessionId,
                        userId,
                        refreshDigest,
                        roleSnapshot,
                        permissionSnapshot,
                        now,
                        now.plus(ttl)
                ),
                ttl
        );
        return new AuthTokenPairBO(accessToken, refreshToken, sessionId);
    }

    @Override
    public Optional<AuthSession> findActiveSession(String sessionId, Long userId) {
        return sessionRepository.findActive(sessionId, userId);
    }
}
