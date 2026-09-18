package com.flowdesk.auth.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.auth.config.AuthProperties;
import com.flowdesk.auth.domain.AuthSession;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/**
 * 会话的 Redis 实现：三个键共用同一有效期 TTL，值以 JSON 存储以便直接读出排查。
 *
 * <p>Redis 写失败会直接抛出，调用方不得在会话未落库的情况下签发访问令牌。</p>
 */
@Repository
public class RedisAuthSessionRepository implements AuthSessionRepository {

    private static final String SESSION_KEY = "flowdesk:auth:session:";
    private static final String REFRESH_KEY = "flowdesk:auth:refresh:";
    private static final String USER_KEY = "flowdesk:auth:user:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AuthProperties authProperties;

    public RedisAuthSessionRepository(StringRedisTemplate redis, ObjectMapper objectMapper,
                                      AuthProperties authProperties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.authProperties = authProperties;
    }

    @Override
    public void save(AuthSession session) {
        Duration ttl = authProperties.refreshExpiration();
        redis.opsForValue().set(SESSION_KEY + session.sessionId(), toJson(session), ttl);
        redis.opsForValue().set(REFRESH_KEY + session.refreshDigest(), session.sessionId(), ttl);

        String userKey = USER_KEY + session.userId();
        redis.opsForSet().add(userKey, session.sessionId());
        redis.expire(userKey, ttl);     // 集合可能已存在，每次续期
    }

    private String toJson(AuthSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("会话序列化失败", ex);
        }
    }
}
