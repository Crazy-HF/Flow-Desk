package com.flowdesk.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.flowdesk.auth.config.AuthProperties;
import com.flowdesk.auth.domain.AuthSession;
import com.flowdesk.auth.domain.RefreshTokenLookup;
import com.flowdesk.auth.domain.RefreshTokenLookup.Status;
import com.flowdesk.auth.infrastructure.RedisAuthSessionRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话读写在真实 Redis 上的验证：键名、TTL、JSON 往返、轮换与撤销都按存储契约检查。
 * 会话仓储在 Web 测试里是替身，序列化与键结构只能在这里覆盖。
 */
@Testcontainers
class RedisAuthSessionRepositoryIT {

    private static final String REDIS_IMAGE = "redis:8.8.0";
    private static final String SESSION_KEY_PREFIX = "flowdesk:auth:session:";
    private static final String REFRESH_KEY_PREFIX = "flowdesk:auth:refresh:";
    private static final String USER_KEY_PREFIX = "flowdesk:auth:user:";
    private static final long USER_ID = 42L;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379);

    private static RedisAuthSessionRepository repository;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void setUp() {
        LettuceConnectionFactory connectionFactory =
                new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();

        // 与生产一致：ISO 字符串而非时间戳，便于直接读值排查
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        repository = new RedisAuthSessionRepository(redis, objectMapper, authProperties());
    }

    @Test
    void savedSessionIsReadBackUnchanged() {
        AuthSession session = session("read-back");

        repository.save(session);

        assertThat(repository.findById(session.sessionId())).contains(session);
    }

    @Test
    void sessionKeyCarriesTheConfiguredTtl() {
        AuthSession session = session("ttl");

        repository.save(session);

        assertThat(redis.getExpire(SESSION_KEY_PREFIX + session.sessionId()))
                .isGreaterThan(0L)
                .isLessThanOrEqualTo(Duration.ofDays(7).toSeconds());
    }

    @Test
    void unknownSessionReturnsEmpty() {
        assertThat(repository.findById("session-that-was-never-created")).isEmpty();
    }

    @Test
    void deletedSessionIsNoLongerVisible() {
        AuthSession session = session("revoked");
        repository.save(session);
        assertThat(repository.findById(session.sessionId())).isPresent();

        redis.delete(SESSION_KEY_PREFIX + session.sessionId());

        assertThat(repository.findById(session.sessionId())).isEmpty();
    }

    @Test
    void corruptedValueIsTreatedAsMissing() {
        String sessionId = "corrupted";
        redis.opsForValue().set(SESSION_KEY_PREFIX + sessionId, "not-a-session");

        Optional<AuthSession> found = repository.findById(sessionId);

        assertThat(found).isEmpty();
    }

    /** 键存在但不是本项目写入的类型时也要按会话无效处理，不能把请求变成 500。 */
    @Test
    void sessionKeyOfForeignTypeIsTreatedAsMissing() {
        String sessionId = "foreign-type";
        redis.opsForHash().put(SESSION_KEY_PREFIX + sessionId, "field", "value");

        assertThat(repository.findById(sessionId)).isEmpty();
    }

    @Test
    void issuedRefreshDigestResolvesToItsSession() {
        AuthSession session = session("issued");
        repository.save(session);

        RefreshTokenLookup lookup = repository.findByRefreshDigest(session.refreshDigest());

        assertThat(lookup.status()).isEqualTo(Status.ACTIVE);
        assertThat(lookup.sessionId()).isEqualTo(session.sessionId());
    }

    @Test
    void unknownRefreshDigestIsReportedAsUnknown() {
        assertThat(repository.findByRefreshDigest("digest-never-issued").status()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void rotationConsumesTheOldDigestAndActivatesTheNewOne() {
        AuthSession session = session("rotate");
        repository.save(session);
        AuthSession rotated = session.withRefreshDigest("digest-rotated");

        repository.rotate(rotated, session.refreshDigest());

        assertThat(repository.findByRefreshDigest(session.refreshDigest()).status()).isEqualTo(Status.REUSED);
        assertThat(repository.findByRefreshDigest(rotated.refreshDigest()).status()).isEqualTo(Status.ACTIVE);
        // 快照必须同步新摘要，否则撤销时删不掉新索引
        assertThat(repository.findById(session.sessionId())).contains(rotated);
        // 轮换不延长会话寿命：新索引必须带 TTL
        assertThat(redis.getExpire(REFRESH_KEY_PREFIX + rotated.refreshDigest())).isGreaterThan(0L);
    }

    /** 重放检测要能反查到会话，撤销整个会话才有目标。 */
    @Test
    void replayedDigestStillIdentifiesItsSession() {
        AuthSession session = session("replay");
        repository.save(session);
        repository.rotate(session.withRefreshDigest("digest-second"), session.refreshDigest());

        RefreshTokenLookup lookup = repository.findByRefreshDigest(session.refreshDigest());

        assertThat(lookup.status()).isEqualTo(Status.REUSED);
        assertThat(lookup.sessionId()).isEqualTo(session.sessionId());
    }

    @Test
    void revokeRemovesSessionRefreshIndexAndUserMembership() {
        AuthSession session = session("revoke");
        repository.save(session);

        repository.revoke(session.sessionId());

        assertThat(repository.findById(session.sessionId())).isEmpty();
        assertThat(repository.findByRefreshDigest(session.refreshDigest()).status()).isEqualTo(Status.UNKNOWN);
        assertThat(redis.opsForSet().members(USER_KEY_PREFIX + USER_ID)).doesNotContain(session.sessionId());
    }

    @Test
    void revokeAfterRotationDeletesTheCurrentIndexAndIsIdempotent() {
        AuthSession session = session("revoke-rotated");
        repository.save(session);
        AuthSession rotated = session.withRefreshDigest("digest-rotated");
        repository.rotate(rotated, session.refreshDigest());

        repository.revoke(session.sessionId());
        repository.revoke(session.sessionId());     // 重复撤销必须保持幂等

        assertThat(repository.findById(session.sessionId())).isEmpty();
        assertThat(repository.findByRefreshDigest(rotated.refreshDigest()).status()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void revokeAllOnlyRemovesTheTargetUsersSessions() {
        AuthSession first = session("revoke-all-first");
        AuthSession second = session("revoke-all-second");
        AuthSession otherUser = session("revoke-all-other-user", 7L);
        repository.save(first);
        repository.save(second);
        repository.save(otherUser);

        repository.revokeAll(USER_ID);

        assertThat(repository.findById(first.sessionId())).isEmpty();
        assertThat(repository.findById(second.sessionId())).isEmpty();
        assertThat(repository.findById(otherUser.sessionId())).isPresent();
        assertThat(redis.opsForSet().members(USER_KEY_PREFIX + USER_ID)).isNullOrEmpty();
    }

    private static AuthSession session(String tag) {
        return session(tag, USER_ID);
    }

    private static AuthSession session(String tag, long userId) {
        Instant createdAt = Instant.now().minus(Duration.ofMinutes(5));
        return new AuthSession("session-" + tag, userId, "employee", "演示员工", "digest-" + tag,
                List.of("EMPLOYEE"), List.of("TICKET_CREATE", "TICKET_VIEW_OWN"),
                createdAt, createdAt.plus(Duration.ofDays(7)));
    }

    private static AuthProperties authProperties() {
        return new AuthProperties(Duration.ofDays(7), 32, "FLOWDESK_REFRESH", "/fd/v1/auth", false,
                List.of("http://localhost"));
    }
}
