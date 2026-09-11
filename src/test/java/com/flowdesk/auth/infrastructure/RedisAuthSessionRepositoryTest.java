package com.flowdesk.auth.infrastructure;

import com.flowdesk.auth.domain.AuthSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisAuthSessionRepositoryTest {

    @Test
    void storesDigestAndLoadsOnlyActiveMatchingSession() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashOperations = mock(HashOperations.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.expire("flowdesk:auth:session:session-1", Duration.ofDays(7)))
                .thenReturn(true);
        RedisAuthSessionRepository repository = new RedisAuthSessionRepository(redisTemplate);
        Instant now = Instant.parse("2026-09-11T06:00:00Z");
        AuthSession session = new AuthSession(
                "session-1",
                42L,
                "refresh-digest",
                List.of("EMPLOYEE"),
                List.of("TICKET_CREATE"),
                now,
                now.plus(Duration.ofDays(7))
        );

        repository.create(session, Duration.ofDays(7));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Object, Object>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(
                eq("flowdesk:auth:session:session-1"), fieldsCaptor.capture());
        assertThat(fieldsCaptor.getValue())
                .containsEntry("refreshTokenDigest", "refresh-digest")
                .doesNotContainValue("raw-refresh-token");
        verify(valueOperations).set(
                "flowdesk:auth:refresh:refresh-digest",
                "session-1",
                Duration.ofDays(7)
        );

        when(hashOperations.entries("flowdesk:auth:session:session-1"))
                .thenReturn(fieldsCaptor.getValue());
        assertThat(repository.findActive("session-1", 42L))
                .contains(session);
        assertThat(repository.findActive("session-1", 99L)).isEmpty();
    }
}
