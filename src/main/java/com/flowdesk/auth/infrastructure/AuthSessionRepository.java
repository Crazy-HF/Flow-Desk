package com.flowdesk.auth.infrastructure;

import com.flowdesk.auth.domain.AuthSession;

import java.time.Duration;
import java.util.Optional;

public interface AuthSessionRepository {

    void create(AuthSession session, Duration ttl);

    Optional<AuthSession> findActive(String sessionId, Long userId);
}
