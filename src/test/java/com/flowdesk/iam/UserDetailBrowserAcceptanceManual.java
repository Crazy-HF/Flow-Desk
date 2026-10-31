package com.flowdesk.iam;

import com.flowdesk.FlowDeskApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 手动浏览器验收；隔离数据库，且不匹配默认 Surefire/Failsafe 类名。 */
@Testcontainers
@ActiveProfiles("demo")
@SpringBootTest(classes = FlowDeskApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18095",
                "flowdesk.ticket.timeout-task-enabled=false",
                "flowdesk.attachment.reconciliation-enabled=false",
                "auth.allowed-origins=http://127.0.0.1:4173",
                "jwt.secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "FLOWDESK_COOKIE_SECURE=false"
        })
class UserDetailBrowserAcceptanceManual {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
            .withDatabaseName("flowdesk_user_detail_browser")
            .withUsername("flowdesk")
            .withPassword("flowdesk");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.8.0")).withExposedPorts(6379);

    private static final Path ATTACHMENTS = attachmentRoot();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("flowdesk.attachment.root", ATTACHMENTS::toString);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    void browserReadsRealUserDetailAndEnforcesAccess() throws Exception {
        Long employeeId = jdbc.queryForObject(
                "SELECT id FROM iam_user WHERE username = 'employee'", Long.class);
        assertThat(employeeId).isPositive();

        Path frontend = Path.of("frontend").toAbsolutePath();
        runFrontend(frontend, List.of("build"), employeeId);
        if (Boolean.getBoolean("flowdesk.user-detail.full-e2e")) {
            runFrontend(frontend, List.of("exec", "playwright", "test", "--reporter=json"),
                    employeeId, false);
        } else {
            runFrontend(frontend, List.of("exec", "playwright", "test",
                    "e2e/user-detail-live.spec.ts", "--workers=1"), employeeId, true);
        }
    }

    private static void runFrontend(Path directory, List<String> arguments,
                                    long employeeId) throws Exception {
        runFrontend(directory, arguments, employeeId, false);
    }

    private static void runFrontend(Path directory, List<String> arguments,
                                    long employeeId, boolean focusedAcceptance) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> command = new java.util.ArrayList<>();
        command.add(windows ? "pnpm.cmd" : "pnpm");
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .inheritIO();
        builder.environment().put("FLOWDESK_API_TARGET", "http://127.0.0.1:18095");
        if (arguments.contains("--reporter=json")) {
            builder.environment().put("PLAYWRIGHT_JSON_OUTPUT_FILE", directory.getParent()
                    .resolve("target/user-detail-full-e2e-results.json").toString());
        }
        if (focusedAcceptance) {
            builder.environment().put("FLOWDESK_USER_DETAIL_BROWSER_ACCEPTANCE", "YES");
            builder.environment().put("FLOWDESK_USER_DETAIL_EMPLOYEE_ID", Long.toString(employeeId));
        } else {
            builder.environment().remove("FLOWDESK_USER_DETAIL_BROWSER_ACCEPTANCE");
            builder.environment().remove("FLOWDESK_USER_DETAIL_EMPLOYEE_ID");
        }
        Process process = builder.start();
        if (!process.waitFor(Duration.ofMinutes(6).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("前端验收进程超时：" + arguments);
        }
        assertThat(process.exitValue()).as("frontend %s", arguments).isZero();
    }

    private static Path attachmentRoot() {
        try {
            return Files.createTempDirectory("flowdesk-user-detail-attachments-").toAbsolutePath();
        } catch (Exception failure) {
            throw new IllegalStateException("无法创建测试附件目录", failure);
        }
    }
}
