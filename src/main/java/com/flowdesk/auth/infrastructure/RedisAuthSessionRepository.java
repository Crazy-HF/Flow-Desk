package com.flowdesk.auth.infrastructure;

import com.flowdesk.auth.domain.AuthSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Redis 会话仓储：保存 Refresh Token 摘要，不保存 JWT 或原始 Refresh Token。 */
@Repository
public class RedisAuthSessionRepository implements AuthSessionRepository {

    static final String SESSION_KEY_PREFIX = "flowdesk:auth:session:";
    static final String REFRESH_KEY_PREFIX = "flowdesk:auth:refresh:";

    private final StringRedisTemplate redisTemplate;

    public RedisAuthSessionRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void create(AuthSession session, Duration ttl) {
        String sessionKey = SESSION_KEY_PREFIX + session.sessionId();
        String refreshKey = REFRESH_KEY_PREFIX + session.refreshTokenDigest();
        try {
            redisTemplate.opsForHash().putAll(sessionKey, Map.of(
                    "userId", session.userId().toString(),
                    "refreshTokenDigest", session.refreshTokenDigest(),
                    "roles", String.join(",", session.roles()),
                    "permissions", String.join(",", session.permissions()),
                    "status", "ACTIVE",
                    "createdAt", session.createdAt().toString(),
                    "expiresAt", session.expiresAt().toString()
            ));
            if (!Boolean.TRUE.equals(redisTemplate.expire(sessionKey, ttl))) {
                throw new IllegalStateException("Failed to set authentication session TTL");
            }
            redisTemplate.opsForValue().set(
                    refreshKey,
                    session.sessionId(),
                    ttl
            );
        } catch (RuntimeException exception) {
            redisTemplate.delete(sessionKey);
            redisTemplate.delete(refreshKey);
            throw exception;
        }
    }

    @Override
    public Optional<AuthSession> findActive(String sessionId, Long userId) {
        if (sessionId == null || sessionId.isBlank() || userId == null) {
            return Optional.empty();
        }
        String sessionKey = SESSION_KEY_PREFIX + sessionId;
        Map<Object, Object> fields = redisTemplate.opsForHash().entries(sessionKey);
        if (!userId.toString().equals(fields.get("userId"))
                || !"ACTIVE".equals(fields.get("status"))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new AuthSession(
                    sessionId,
                    userId,
                    String.valueOf(fields.get("refreshTokenDigest")),
                    splitCodes(fields.get("roles")),
                    splitCodes(fields.get("permissions")),
                    Instant.parse(String.valueOf(fields.get("createdAt"))),
                    Instant.parse(String.valueOf(fields.get("expiresAt")))
            ));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private List<String> splitCodes(Object value) {
        if (value == null || value.toString().isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.toString().split(","))
                .filter(code -> !code.isBlank())
                .toList();
    }
}
