package com.flowdesk.auth.service;

import com.flowdesk.auth.domain.AuthSession;
import com.flowdesk.auth.domain.bo.AuthTokenPairBO;

import java.util.Collection;
import java.util.Optional;

/** 登录会话与 Token 的边界，不直接暴露 Redis 操作。 */
public interface TokenService {

    AuthTokenPairBO createLoginTokens(
            Long userId,
            String username,
            Collection<String> roles,
            Collection<String> permissions
    );

    Optional<AuthSession> findActiveSession(String sessionId, Long userId);
}
