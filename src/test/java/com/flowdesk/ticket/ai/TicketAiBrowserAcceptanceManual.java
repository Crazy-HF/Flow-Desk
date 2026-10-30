package com.flowdesk.ticket.ai;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.ticket.application.port.TicketAiModelPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手动真实浏览器验收入口；不匹配默认 Surefire/Failsafe 类名模式。
 * MySQL/Redis 只在临时容器中运行，模型由测试 Bean 接管，不连接供应商。
 */
@Testcontainers
@ActiveProfiles("demo")
@Import(TicketAiBrowserAcceptanceManual.FakeModel.class)
@SpringBootTest(classes = FlowDeskApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=18094",
                "flowdesk.ai.enabled=true",
                "flowdesk.ai.retrieval-enabled=true",
                "DEEPSEEK_API_KEY=browser-acceptance-placeholder",
                "flowdesk.ticket.timeout-task-enabled=false",
                "flowdesk.attachment.reconciliation-enabled=false",
                "auth.allowed-origins=http://127.0.0.1:4173",
                "jwt.secret=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "FLOWDESK_COOKIE_SECURE=false"
        })
class TicketAiBrowserAcceptanceManual {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
            .withDatabaseName("flowdesk_ai_browser")
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
    void browserUsesRealHttpAndMySqlForPreviewGenerateAndHistory() throws Exception {
        long employee = jdbc.queryForObject(
                "SELECT id FROM iam_user WHERE username = 'employee'", Long.class);
        long it = jdbc.queryForObject("SELECT id FROM iam_user WHERE username = 'it'", Long.class);
        long category = jdbc.queryForObject(
                "SELECT id FROM ticket_category WHERE name = '办公设备'", Long.class);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sourceNo = "FD-AI-LIVE-S-" + suffix;
        String currentNo = "FD-AI-LIVE-C-" + suffix;

        // 两张工单都只含虚构内容；来源的最后一条 PROCESS 才可进入预览。
        jdbc.update("""
                INSERT INTO ticket (ticket_no, submission_key, requester_id, title, description,
                    category_id, priority, status, assignee_id, close_method, close_reason,
                    ended_at, record_seq, version, created_at, updated_at)
                VALUES (?, ?, ?, '虚构投影仪来源', '仅供浏览器验收', ?, 'MEDIUM', 'CLOSED', ?,
                    'MANUAL', 'INVALID', CURRENT_TIMESTAMP(3), 1, 0,
                    CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """, sourceNo, UUID.randomUUID().toString(), employee, category, it);
        long sourceId = jdbc.queryForObject(
                "SELECT id FROM ticket WHERE ticket_no = ?", Long.class, sourceNo);
        jdbc.update("""
                INSERT INTO ticket_record (ticket_id, sequence_no, record_type, actor_type,
                    actor_user_id, content, created_at)
                VALUES (?, 1, 'PROCESS', 'USER', ?, '先核实连接线与输入源。', CURRENT_TIMESTAMP(3))
                """, sourceId, it);
        jdbc.update("""
                INSERT INTO ticket (ticket_no, submission_key, requester_id, title, description,
                    category_id, priority, status, assignee_id, record_seq, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, '虚构投影仪报障 13800000000',
                    '连接后无信号；示例手机号 13800000000 应在确认框删去。',
                    ?, 'MEDIUM', 'PROCESSING', ?, 0, 0,
                    CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """, currentNo, UUID.randomUUID().toString(), employee, category, it);

        Path frontend = Path.of("frontend").toAbsolutePath();
        runFrontend(frontend, List.of("build"), currentNo);
        runFrontend(frontend, List.of("exec", "playwright", "test",
                "e2e/ticket-ai-live.spec.ts", "--workers=1"), currentNo);
        Integer persisted = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket_ai_suggestion s
                JOIN ticket t ON t.id = s.ticket_id
                WHERE t.ticket_no = ?
                """, Integer.class, currentNo);
        assertThat(persisted).isEqualTo(1);
    }

    private static void runFrontend(Path directory, List<String> arguments,
                                    String ticketNo) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        List<String> command = new java.util.ArrayList<>();
        command.add(windows ? "pnpm.cmd" : "pnpm");
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
                .inheritIO();
        builder.environment().put("FLOWDESK_API_TARGET", "http://127.0.0.1:18094");
        builder.environment().put("FLOWDESK_AI_BROWSER_ACCEPTANCE", "YES");
        builder.environment().put("FLOWDESK_AI_LIVE_TICKET_NO", ticketNo);
        Process process = builder.start();
        if (!process.waitFor(Duration.ofMinutes(6).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("前端验收进程超时：" + arguments);
        }
        assertThat(process.exitValue()).as("frontend %s", arguments).isZero();
    }

    private static Path attachmentRoot() {
        try {
            return Files.createTempDirectory("flowdesk-ai-browser-attachments-").toAbsolutePath();
        } catch (Exception failure) {
            throw new IllegalStateException("无法创建测试附件目录", failure);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeModel {
        @Bean
        @Primary
        TicketAiModelPort acceptanceModel() {
            return input -> new TicketAiModelPort.Output(
                    "虚构投影仪无信号，当前原因仍待核实。",
                    List.of("输入源是否与接入接口一致？"),
                    List.of(input.sources().isEmpty()
                            ? "先检查线缆与输入源。"
                            : "参考 [S1] 先检查线缆与输入源。"),
                    "deepseek-flash", input.sources().isEmpty() ? "v4" : "v5", 100, 60);
        }
    }
}
