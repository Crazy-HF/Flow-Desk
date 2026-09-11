package com.flowdesk.auth.domain;

import java.time.Instant;
import java.util.List;

/** Redis 中登录会话的最小安全快照，不保存原始 Refresh Token。 */
public record AuthSession(
        String sessionId,
        Long userId,
        String refreshTokenDigest,
        List<String> roles,
        List<String> permissions,
        Instant createdAt,
        Instant expiresAt
) {
}
