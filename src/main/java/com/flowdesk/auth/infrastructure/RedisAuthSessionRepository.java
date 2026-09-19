package com.flowdesk.auth.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.auth.config.AuthProperties;
import com.flowdesk.auth.domain.AuthSession;
import com.flowdesk.auth.domain.RefreshTokenLookup;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * 会话的 Redis 实现：三个键共用同一有效期 TTL，值以 JSON 存储以便直接读出排查。
 *
 * <p>Redis 写失败会直接抛出，调用方不得在会话未落库的情况下签发访问令牌。</p>
 */
@Slf4j
@Repository
public class RedisAuthSessionRepository implements AuthSessionRepository {

    // 存储会话完整信息的 JSON
    private static final String SESSION_KEY = "flowdesk:auth:session:";
    // 存储 refresh token 摘要与会话 ID 的映射
    private static final String REFRESH_KEY = "flowdesk:auth:refresh:";
    // 存储用户 ID 与会话 ID 的集合
    private static final String USER_KEY = "flowdesk:auth:user:";
    // 标记已被使用的旧 token
    private static final String CONSUMED_PREFIX = "consumed:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AuthProperties authProperties;

    public RedisAuthSessionRepository(StringRedisTemplate redis, ObjectMapper objectMapper,
                                      AuthProperties authProperties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.authProperties = authProperties;
    }

    /**
     * 保存会话
     * @param session: 会话
     */
    @Override
    public void save(AuthSession session) {
        // 获取有效期
        Duration ttl = authProperties.refreshExpiration();
        // 保存会话完整信息
        redis.opsForValue().set(SESSION_KEY + session.sessionId(), toJson(session), ttl);
        // 保存 refresh token 摘要与会话 ID 的映射
        redis.opsForValue().set(REFRESH_KEY + session.refreshDigest(), session.sessionId(), ttl);

        // 保存用户 ID 与会话 ID 的集合
        String userKey = USER_KEY + session.userId();
        redis.opsForSet().add(userKey, session.sessionId());
        redis.expire(userKey, ttl);     // 集合可能已存在，每次续期
    }

    /**
     * 根据会话 ID 查找会话
     * @param sessionId: 会话 ID
     * @return
     */
    @Override
    public Optional<AuthSession> findById(String sessionId) {
        /**
         * 用登录时已经有的那个键名常量加上 sessionId 去 Redis 取，
         * 取不到就返回"空"，取到了就把那段 JSON 还原成 AuthSession 对象；
         * 如果那段内容坏了还原不了，也当作"空"返回（不要让请求崩掉）
         */
        String json;
        try {
            json = redis.opsForValue().get(SESSION_KEY + sessionId);
        } catch (RedisSystemException ex) {
            // Redis 拒绝执行这条读取（例如键存在但不是字符串类型）：这种键不可能是本类写出来的，
            // 按会话无效处理，避免一个残留值把"撤销全部会话"这类批量操作整条带崩。
            // 只捕获它、不捕获连接类异常：Redis 连不上仍应显式失败，不能伪装成"会话不存在"。
            log.warn("会话键无法读取，按会话无效处理 sessionId={}", sessionId, ex);
            return Optional.empty();
        }
        if (json == null) {
            // 查不到会话是正常现象：过期、退出或撤销都会走到这里，不记为异常
            return Optional.empty();
        }
        try {
            // 将 JSON 还原成 AuthSession 对象
            return Optional.ofNullable(objectMapper.readValue(json, AuthSession.class));
        } catch (JsonProcessingException ex) {
            // 值由本类写入，解析失败说明存在脏数据：按会话无效处理，不让请求崩掉
            log.warn("会话快照无法解析，按会话无效处理 sessionId={}", sessionId, ex);
            return Optional.empty();
        }
    }

    /**
     * 将会话对象转换为 JSON 字符串
     * @param session
     * @return
     */
    private String toJson(AuthSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("会话序列化失败", ex);
        }
    }

    /**
     * 根据 refresh token 的摘要查找会话
     * @param refreshDigest: refresh token 的摘要
     * @return
     */
    @Override
    public RefreshTokenLookup findByRefreshDigest(String refreshDigest) {
        // 根据 refresh token 的摘要查找会话
        String value = redis.opsForValue().get(REFRESH_KEY + refreshDigest);
        if (value == null) {
            return RefreshTokenLookup.unknown();
        }
        // 判断 refresh token 是否已使用
        if (value.startsWith(CONSUMED_PREFIX)) {
            return RefreshTokenLookup.reused(value.substring(CONSUMED_PREFIX.length()));
        }
        return RefreshTokenLookup.active(value);
    }

    /**
     * 轮换 Refresh Token：把旧摘要标记为已使用、写入新摘要索引，并更新会话快照中的摘要。
     * @param session: 待轮换的会话
     * @param previousDigest: 旧 refresh token 的摘要
     */
    @Override
    public void rotate(AuthSession session, String previousDigest) {
        // 获取剩余有效期
        Duration ttl = remainingTtl(session.sessionId());
        // 顺序固定为"先作废旧摘要、再启用新摘要、最后更新快照"：任何一步失败都不会让旧令牌继续可用。
        // 代价是失败后用户可能需要重新登录，这比旧令牌仍可轮换要好。
        redis.opsForValue().set(REFRESH_KEY + previousDigest, CONSUMED_PREFIX + session.sessionId(), ttl);
        redis.opsForValue().set(REFRESH_KEY + session.refreshDigest(), session.sessionId(), ttl);
        // 快照里的摘要必须同步，否则撤销时会删不掉新索引
        redis.opsForValue().set(SESSION_KEY + session.sessionId(), toJson(session), ttl);
    }

    /**
     * 撤销会话
     */
    @Override
    public void revoke(String sessionId) {
        AuthSession session = findById(sessionId).orElse(null);
        if (session == null) {
            // 已经撤销或已经过期：保持幂等，不区分"是否曾经存在"
            return;
        }
        redis.delete(REFRESH_KEY + session.refreshDigest());
        redis.delete(SESSION_KEY + sessionId);
        redis.opsForSet().remove(USER_KEY + session.userId(), sessionId);
    }

    /** 会话键的剩余有效期：轮换出来的新键必须与它同时过期，否则刷新会变相延长会话。 */
    private Duration remainingTtl(String sessionId) {
        Long seconds = redis.getExpire(SESSION_KEY + sessionId);
        if (seconds == null || seconds <= 0) {
            throw new IllegalStateException("会话键缺少有效 TTL，sessionId=" + sessionId);
        }
        return Duration.ofSeconds(seconds);
    }

    /**
     * 撤销用户所有会话
     * @param userId
     */
    @Override
    public void revokeAll(long userId) {
        String userKey = USER_KEY + userId;
        Set<String> sessionIds = redis.opsForSet().members(userKey);

        if (sessionIds == null || sessionIds.isEmpty())
            return;

        // members 返回的是快照，遍历中逐个撤销不受集合变动影响；revoke 自身幂等
        sessionIds.forEach(this::revoke);
        redis.delete(userKey);
    }
}
