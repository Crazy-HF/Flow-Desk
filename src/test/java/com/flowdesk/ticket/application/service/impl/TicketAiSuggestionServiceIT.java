package com.flowdesk.ticket.application.service.impl;

import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.ticket.application.command.GenerateTicketAiSuggestionCommand;
import com.flowdesk.ticket.application.port.TicketAiModelPort;
import com.flowdesk.ticket.application.query.TicketAiSuggestionQuery;
import com.flowdesk.ticket.application.service.TicketAiSuggestionService;
import com.flowdesk.ticket.infrastructure.TicketAiRetentionTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** AI 生成链路与历史清理的真实 MySQL 验证；模型由替身实现，不发生付费调用。 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "flowdesk.ai.enabled=true",
        "flowdesk.ai.daily-limit=2",
        "DEEPSEEK_API_KEY=integration-test-placeholder"
})
class TicketAiSuggestionServiceIT {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.11")
            .withDatabaseName("flowdesk_ticket_ai_it")
            .withUsername("flowdesk")
            .withPassword("flowdesk");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired private TicketAiSuggestionService service;
    @Autowired private TicketAiRetentionTask retentionTask;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private AuthSessionRepository authSessionRepository;
    @MockitoBean private TicketAiModelPort modelPort;

    private long requesterId;
    private long assigneeId;
    private long nextAssigneeId;
    private long categoryId;
    private long ticketId;
    private String ticketNo;

    @BeforeEach
    void prepareTicket() {
        reset(modelPort);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        requesterId = insertUser("ai-requester-" + suffix, "EMPLOYEE");
        assigneeId = insertUser("ai-assignee-" + suffix, "IT_SUPPORT");
        nextAssigneeId = insertUser("ai-next-" + suffix, "IT_SUPPORT");
        jdbc.update("""
                INSERT INTO ticket_category (name, status, sort_order, created_at, updated_at, version)
                VALUES (?, 'ENABLED', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0)
                """, "AI-CATEGORY-" + suffix);
        categoryId = jdbc.queryForObject(
                "SELECT id FROM ticket_category WHERE name = ?", Long.class, "AI-CATEGORY-" + suffix);
        ticketNo = "FD-AI-" + suffix;
        jdbc.update("""
                INSERT INTO ticket (
                    ticket_no, submission_key, requester_id, title, description,
                    category_id, priority, status, assignee_id,
                    record_seq, version, created_at, updated_at
                ) VALUES (?, ?, ?, '投影仪无信号', '三楼会议室投影仪无信号',
                          ?, 'MEDIUM', 'PROCESSING', ?, 0, 0,
                          CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """, ticketNo, UUID.randomUUID().toString(), requesterId, categoryId, assigneeId);
        ticketId = jdbc.queryForObject(
                "SELECT id FROM ticket WHERE ticket_no = ?", Long.class, ticketNo);
        authenticateAs(assigneeId);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM ticket_ai_suggestion WHERE ticket_id = ?", ticketId);
        jdbc.update("DELETE FROM ticket_ai_request WHERE ticket_id = ?", ticketId);
        jdbc.update("DELETE FROM ticket_ai_daily_quota WHERE user_id IN (?, ?, ?)",
                requesterId, assigneeId, nextAssigneeId);
        jdbc.update("DELETE FROM ticket_participant WHERE ticket_id = ?", ticketId);
        jdbc.update("DELETE FROM ticket WHERE id = ?", ticketId);
        jdbc.update("DELETE FROM ticket_category WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM iam_user_role WHERE user_id IN (?, ?, ?)",
                requesterId, assigneeId, nextAssigneeId);
        jdbc.update("DELETE FROM iam_user WHERE id IN (?, ?, ?)",
                requesterId, assigneeId, nextAssigneeId);
    }

    @Test
    void successPersistsSnapshotAndReplayDoesNotCallModelTwice() {
        when(modelPort.generate(any())).thenReturn(answer());
        var command = command();

        var first = service.generate(ticketNo, command);
        var replay = service.generate(ticketNo, command);

        assertThat(first.id()).isEqualTo(replay.id());
        assertThat(first.input().title()).isEqualTo("删敏后的投影仪问题");
        assertThat(first.input().description()).isEqualTo("只描述设备现象");
        assertThat(first.questions()).containsExactly("输入源是否正确？");
        assertThat(service.history(ticketNo, new TicketAiSuggestionQuery()).items())
                .extracting(item -> item.id()).containsExactly(first.id());
        assertThat(count("ticket_ai_request")).isEqualTo(1);
        assertThat(count("ticket_ai_suggestion")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT used_count FROM ticket_ai_daily_quota WHERE user_id = ?",
                Integer.class, assigneeId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT record_seq FROM ticket WHERE id = ?", Integer.class, ticketId)).isZero();
        verify(modelPort, times(1)).generate(any());
    }

    @Test
    void dailyLimitRejectsThirdRequestWithoutCallingModel() {
        when(modelPort.generate(any())).thenReturn(answer());
        service.generate(ticketNo, command());
        service.generate(ticketNo, command());

        ApiException rejected = catchThrowableOfType(
                () -> service.generate(ticketNo, command()), ApiException.class);
        assertThat(rejected.code()).isEqualTo("AI_DAILY_LIMIT_REACHED");
        assertThat(rejected.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(count("ticket_ai_request")).isEqualTo(2);
        assertThat(count("ticket_ai_suggestion")).isEqualTo(2);
        verify(modelPort, times(2)).generate(any());
    }

    @Test
    void modelFailureConsumesQuotaButDoesNotSaveSuggestion() {
        when(modelPort.generate(any())).thenThrow(
                new TicketAiModelPort.ModelFailure(TicketAiModelPort.FailureKind.TIMEOUT, null));

        ApiException rejected = catchThrowableOfType(
                () -> service.generate(ticketNo, command()), ApiException.class);
        assertThat(rejected.code()).isEqualTo("AI_PROVIDER_TIMEOUT");
        assertThat(count("ticket_ai_suggestion")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ticket_ai_request WHERE ticket_id = ?",
                String.class, ticketId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT used_count FROM ticket_ai_daily_quota WHERE user_id = ?",
                Integer.class, assigneeId)).isEqualTo(1);
        verify(modelPort, times(1)).generate(any());
    }

    @Test
    void changedTicketDuringModelCallMarksRequestStale() {
        when(modelPort.generate(any())).thenAnswer(ignored -> {
            jdbc.update("UPDATE ticket SET version = version + 1 WHERE id = ?", ticketId);
            return answer();
        });

        ApiException rejected = catchThrowableOfType(
                () -> service.generate(ticketNo, command()), ApiException.class);
        assertThat(rejected.code()).isEqualTo("TICKET_CONFLICT");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM ticket_ai_request WHERE ticket_id = ?",
                String.class, ticketId)).isEqualTo("STALE");
        assertThat(count("ticket_ai_suggestion")).isZero();
    }

    @Test
    void currentOrLastAssigneeOnlyCanReadHistoryAndExpiredHistoryIsHidden() {
        when(modelPort.generate(any())).thenReturn(answer());
        service.generate(ticketNo, command());

        authenticateAs(requesterId);
        ApiException denied = catchThrowableOfType(
                () -> service.history(ticketNo, new TicketAiSuggestionQuery()), ApiException.class);
        assertThat(denied.code()).isEqualTo("AI_HISTORY_FORBIDDEN");

        jdbc.update("UPDATE ticket SET assignee_id = ?, version = version + 1 WHERE id = ?",
                nextAssigneeId, ticketId);
        // 旧负责人仍可按参与历史查看工单；AI 历史要再被更窄的负责人规则拦下。
        jdbc.update("""
                INSERT INTO ticket_participant
                    (ticket_id, user_id, first_assigned_at, last_assigned_at)
                VALUES (?, ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """, ticketId, assigneeId);
        authenticateAs(assigneeId);
        denied = catchThrowableOfType(
                () -> service.history(ticketNo, new TicketAiSuggestionQuery()), ApiException.class);
        assertThat(denied.code()).isEqualTo("AI_HISTORY_FORBIDDEN");

        authenticateAs(nextAssigneeId);
        assertThat(service.history(ticketNo, new TicketAiSuggestionQuery()).items()).hasSize(1);

        LocalDateTime expired = LocalDateTime.now(ZoneOffset.UTC).minusDays(31);
        jdbc.update("""
                UPDATE ticket SET status = 'COMPLETED', completion_method = 'REQUESTER_CONFIRMED',
                                  ended_at = ?, version = version + 1 WHERE id = ?
                """, expired, ticketId);
        assertThat(service.history(ticketNo, new TicketAiSuggestionQuery()).items()).isEmpty();

        retentionTask.cleanExpiredHistory();
        assertThat(count("ticket_ai_suggestion")).isZero();
        assertThat(count("ticket_ai_request")).isZero();
    }

    @Test
    void requesterCannotGenerateEvenWhenTicketIsVisible() {
        authenticateAs(requesterId);
        ApiException rejected = catchThrowableOfType(
                () -> service.generate(ticketNo, command()), ApiException.class);
        assertThat(rejected.code()).isEqualTo("AI_GENERATION_FORBIDDEN");
        assertThat(count("ticket_ai_request")).isZero();
        verify(modelPort, never()).generate(any());
    }

    private GenerateTicketAiSuggestionCommand command() {
        return new GenerateTicketAiSuggestionCommand(
                UUID.randomUUID().toString(), 0L, "删敏后的投影仪问题", "只描述设备现象");
    }

    private TicketAiModelPort.Output answer() {
        return new TicketAiModelPort.Output(
                "投影仪连接后无信号，原因待查。",
                List.of("输入源是否正确？"),
                List.of("检查线缆和输入源。"),
                "deepseek-flash", "v1", 30, 80);
    }

    private int count(String table) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE ticket_id = ?",
                Integer.class, ticketId);
    }

    private long insertUser(String username, String roleCode) {
        jdbc.update("""
                INSERT INTO iam_user (username, display_name, password, status,
                                      created_at, updated_at, version)
                VALUES (?, ?, 'integration-test-hash', 'ENABLED',
                        CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0)
                """, username, username);
        long id = jdbc.queryForObject(
                "SELECT id FROM iam_user WHERE username = ?", Long.class, username);
        jdbc.update("""
                INSERT INTO iam_user_role (user_id, role_id, granted_by, granted_at)
                SELECT ?, id, NULL, CURRENT_TIMESTAMP(3) FROM iam_role WHERE code = ?
                """, id, roleCode);
        return id;
    }

    /** 与生产认证过滤器相同，身份及权限从真实角色授权行建立。 */
    private void authenticateAs(long userId) {
        String username = jdbc.queryForObject(
                "SELECT username FROM iam_user WHERE id = ?", String.class, userId);
        var authorities = jdbc.queryForList("""
                SELECT DISTINCT permission.code
                FROM iam_user_role user_role
                JOIN iam_role_permission role_permission ON role_permission.role_id = user_role.role_id
                JOIN iam_permission permission ON permission.id = role_permission.permission_id
                WHERE user_role.user_id = ?
                """, String.class, userId).stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(userId, username, "ai-it-session"), null, authorities));
    }
}
