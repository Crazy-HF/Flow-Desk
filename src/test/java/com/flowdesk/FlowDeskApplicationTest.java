package com.flowdesk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.support.MockedPersistenceConfiguration;

@ActiveProfiles("test")
@Import(MockedPersistenceConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                    + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration"
        })
class FlowDeskApplicationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    /** 本上下文排除了 Redis 自动配置，会话仓储以替身提供。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    @Test
    void exposesHealthWithoutDefaultCredentials() {
        var response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
