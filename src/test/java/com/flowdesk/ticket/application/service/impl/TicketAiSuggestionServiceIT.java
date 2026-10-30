package com.flowdesk.ticket.application.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.ticket.application.command.GenerateTicketAiSuggestionCommand;
import com.flowdesk.ticket.application.port.TicketAiModelPort;
import com.flowdesk.ticket.application.query.TicketAiSuggestionQuery;
import com.flowdesk.ticket.application.service.TicketAiSuggestionService;
import com.flowdesk.ticket.config.TicketAiProperties;
import com.flowdesk.ticket.infrastructure.TicketAiRetentionTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AI 生成链路与历史清理的真实 MySQL 验证；模型由替身实现，不发生付费调用。 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "flowdesk.ai.enabled=true",
        "flowdesk.ai.retrieval-enabled=true",
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
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private AuthSessionRepository authSessionRepository;
    @MockitoBean private TicketAiModelPort modelPort;

    private long requesterId;
    private long assigneeId;
    private long nextAssigneeId;
    private long categoryId;
    private long ticketId;
    private String ticketNo;
    private Long planCategoryId;

    @BeforeEach
    void prepareTicket() {
        reset(modelPort);
        planCategoryId = null;
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
        jdbc.update("""
                DELETE FROM ticket_record WHERE ticket_id IN
                    (SELECT id FROM ticket WHERE requester_id = ? AND id <> ?)
                """, requesterId, ticketId);
        jdbc.update("DELETE FROM ticket WHERE requester_id = ? AND id <> ?",
                requesterId, ticketId);
        jdbc.update("DELETE FROM ticket_category WHERE id = ?", categoryId);
        if (planCategoryId != null) {
            jdbc.update("DELETE FROM ticket_category WHERE id = ?", planCategoryId);
        }
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

    @Test
    void abandonedReservationBecomesUncertainWithoutCallingModelAgain() {
        String requestId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO ticket_ai_request
                    (ticket_id, user_id, request_id, ticket_version, input_sha256,
                     status, created_at)
                VALUES (?, ?, ?, 0, REPEAT('a', 64), 'RESERVED', ?)
                """, ticketId, assigneeId, requestId,
                LocalDateTime.now(ZoneOffset.UTC).minusHours(2));

        retentionTask.resolveAbandonedRequests();

        assertThat(jdbc.queryForObject("""
                SELECT status FROM ticket_ai_request
                WHERE user_id = ? AND request_id = ?
                """, String.class, assigneeId, requestId)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("""
                SELECT failure_code FROM ticket_ai_request
                WHERE user_id = ? AND request_id = ?
                """, String.class, assigneeId, requestId)).isEqualTo("AI_RESULT_UNCERTAIN");
        verify(modelPort, never()).generate(any());
    }

    @Test
    void previewAndSnapshotRespectSourceVisibilityAndHistoryPaginationAfterTransfer() {
        when(modelPort.generate(any())).thenReturn(answer());
        var withoutSources = service.generate(ticketNo, command());
        assertThat(withoutSources.sources()).isEmpty();

        insertClosedSource("可见来源", assigneeId, "先核实连接线");
        insertClosedSource("不可见来源", nextAssigneeId, "不得进入模型输入");
        var preview = service.previewSources(ticketNo, 0L);
        assertThat(preview.sources()).hasSize(1);
        assertThat(preview.sources().getFirst().title()).isEqualTo("可见来源");
        assertThat(preview.sources().getFirst().processExcerpt()).isEqualTo("先核实连接线");
        verify(modelPort, times(1)).generate(any()); // 预览没有额外模型调用。

        var withSources = service.generate(ticketNo, new GenerateTicketAiSuggestionCommand(
                UUID.randomUUID().toString(), 0L, "删敏后的投影仪问题", "只描述设备现象",
                preview.sourceDigest()));
        assertThat(withSources.sources()).hasSize(1);
        assertThat(withSources.sources().getFirst().label()).isEqualTo("[S1]");
        assertThat(withSources.sources().getFirst().processExcerpt()).isEqualTo("先核实连接线");
        ArgumentCaptor<TicketAiModelPort.Input> modelInputs =
                ArgumentCaptor.forClass(TicketAiModelPort.Input.class);
        verify(modelPort, times(2)).generate(modelInputs.capture());
        assertThat(modelInputs.getAllValues().getFirst().sources()).isEmpty();
        assertThat(modelInputs.getAllValues().getLast().sources())
                .containsExactly(new TicketAiModelPort.Source(
                        "[S1]", "可见来源", "先核实连接线"));
        assertThat(modelInputs.getAllValues().getLast().toString())
                .doesNotContain(withSources.sources().getFirst().ticketNo());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_ai_suggestion_source",
                Integer.class)).isEqualTo(1);
        assertThat(service.history(ticketNo, new TicketAiSuggestionQuery()).items()).hasSize(2);

        // 新负责人能读目标工单，但不能读旧来源；SQL 分页及总数只保留无来源建议。
        jdbc.update("UPDATE ticket SET assignee_id = ?, version = version + 1 WHERE id = ?",
                nextAssigneeId, ticketId);
        authenticateAs(nextAssigneeId);
        var history = service.history(ticketNo, new TicketAiSuggestionQuery());
        assertThat(history.items()).extracting(item -> item.id())
                .containsExactly(withoutSources.id());
        assertThat(history.totalElements()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_ai_suggestion_source",
                Integer.class)).isEqualTo(1); // 失权隐藏，不物理删除。

        jdbc.update("""
                UPDATE ticket SET status = 'COMPLETED', completion_method = 'REQUESTER_CONFIRMED',
                                  ended_at = ?, version = version + 1 WHERE id = ?
                """, LocalDateTime.now(ZoneOffset.UTC).minusDays(31), ticketId);
        retentionTask.cleanExpiredHistory();
        assertThat(count("ticket_ai_suggestion")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_ai_suggestion_source",
                Integer.class)).isZero();
    }

    @Test
    void retrievalRollbackUsesBaseInputWhileKeepingPriorSourceHistoryReadable() {
        insertClosedSource("可见来源", assigneeId, "先核实连接线");
        when(modelPort.generate(any())).thenReturn(answer());
        var preview = service.previewSources(ticketNo, 0L);
        var previous = service.generate(ticketNo, new GenerateTicketAiSuggestionCommand(
                UUID.randomUUID().toString(), 0L, "删敏后的投影仪问题", "只描述设备现象",
                preview.sourceDigest()));
        assertThat(previous.sources()).hasSize(1);

        // 模拟 A1 回退开关；只变更新请求，旧建议继续按快照授权读取。
        TicketAiProperties original = (TicketAiProperties) ReflectionTestUtils.getField(
                service, "aiProperties");
        try {
            ReflectionTestUtils.setField(service, "aiProperties",
                    new TicketAiProperties(true, 2, false));
            var afterRollback = service.previewSources(ticketNo, 0L);
            assertThat(afterRollback.sources()).isEmpty();
            var fresh = service.generate(ticketNo, command());
            assertThat(fresh.sources()).isEmpty();
            assertThat(service.history(ticketNo, new TicketAiSuggestionQuery()).items())
                    .extracting(item -> item.id()).contains(previous.id(), fresh.id());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_ai_suggestion_source",
                    Integer.class)).isEqualTo(1);
            ArgumentCaptor<TicketAiModelPort.Input> sent =
                    ArgumentCaptor.forClass(TicketAiModelPort.Input.class);
            verify(modelPort, times(2)).generate(sent.capture());
            assertThat(sent.getAllValues().getFirst().sources()).hasSize(1);
            assertThat(sent.getAllValues().getLast().sources()).isEmpty();
        } finally {
            ReflectionTestUtils.setField(service, "aiProperties", original);
        }
    }

    @Test
    void changedSourceDigestRejectsBeforeQuotaAndModelCall() {
        long sourceId = insertClosedSource("虚构来源", assigneeId, "检查连接线");
        var preview = service.previewSources(ticketNo, 0L);
        jdbc.update("UPDATE ticket_record SET content = '检查输入源' WHERE ticket_id = ?", sourceId);

        ApiException rejected = catchThrowableOfType(() -> service.generate(ticketNo,
                new GenerateTicketAiSuggestionCommand(UUID.randomUUID().toString(), 0L,
                        "删敏后的投影仪问题", "只描述设备现象", preview.sourceDigest())),
                ApiException.class);
        assertThat(rejected.code()).isEqualTo("AI_SOURCES_CHANGED");
        assertThat(count("ticket_ai_request")).isZero();
        verify(modelPort, never()).generate(any());
    }

    @Test
    void httpPreviewGenerateAndHistoryUseRealDatabaseWithModelSubstitute() throws Exception {
        insertClosedSource("虚构来源", assigneeId, "核实连接线");
        when(modelPort.generate(any())).thenReturn(answer());
        String path = "/fd/v1/tickets/" + ticketNo + "/ai-suggestions";
        Authentication operator = SecurityContextHolder.getContext().getAuthentication();

        var previewResponse = mockMvc.perform(get(path + "/sources-preview")
                        .with(authentication(operator)).queryParam("version", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources[0].label").value("[S1]"))
                .andExpect(jsonPath("$.data.sources[0].ticketNo").doesNotExist())
                .andReturn();
        String digest = objectMapper.readTree(previewResponse.getResponse().getContentAsString())
                .path("data").path("sourceDigest").asText();

        mockMvc.perform(post(path).with(authentication(operator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestId":"%s","version":0,
                                 "title":"删敏后的投影仪问题","description":"只描述设备现象",
                                 "sourceDigest":"%s"}
                                """.formatted(UUID.randomUUID(), digest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sources[0].label").value("[S1]"))
                .andExpect(jsonPath("$.data.sources[0].processExcerpt").value("核实连接线"));

        mockMvc.perform(get(path).with(authentication(operator))
                        .queryParam("pageNo", "1").queryParam("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].sources[0].label").value("[S1]"));
        verify(modelPort, times(1)).generate(any());
    }

    @Test
    void sourceRecallAvoidsTicketFullScanOnRepresentativeData() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO ticket_category (name, status, sort_order, created_at, updated_at, version)
                VALUES (?, 'ENABLED', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0)
                """, "AI-PLAN-OTHER-" + suffix);
        planCategoryId = jdbc.queryForObject("SELECT id FROM ticket_category WHERE name = ?",
                Long.class, "AI-PLAN-OTHER-" + suffix);

        // 2000 张虚构已结工单：目标类别 200 张，其他类别 1800 张；部分才有处理记录。
        jdbc.batchUpdate("""
                INSERT INTO ticket (ticket_no, submission_key, requester_id, title, description,
                    category_id, priority, status, assignee_id, close_method, close_reason,
                    ended_at, record_seq, version, created_at, updated_at)
                VALUES (?, ?, ?, '虚构来源', '仅用于计划检查', ?, 'MEDIUM', 'CLOSED', ?,
                    'MANUAL', 'INVALID', CURRENT_TIMESTAMP(3), 0, 0,
                    CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                statement.setString(1, "FD-PLAN-" + suffix + "-" + index);
                statement.setString(2, UUID.randomUUID().toString());
                statement.setLong(3, requesterId);
                statement.setLong(4, index < 200 ? categoryId : planCategoryId);
                statement.setLong(5, assigneeId);
            }

            @Override
            public int getBatchSize() {
                return 2000;
            }
        });
        jdbc.update("""
                INSERT INTO ticket_record (ticket_id, sequence_no, record_type, actor_type,
                    actor_user_id, content, created_at)
                SELECT id, 1, 'PROCESS', 'USER', ?, '虚构处理摘录', CURRENT_TIMESTAMP(3)
                FROM ticket WHERE requester_id = ? AND category_id = ? AND id <> ?
                ORDER BY id DESC LIMIT 50
                """, assigneeId, requesterId, categoryId, ticketId);
        jdbc.execute("ANALYZE TABLE ticket");
        jdbc.execute("ANALYZE TABLE ticket_record");

        // 与生产来源 Mapper 相同的主表谓词和排序，保留 PROCESS EXISTS 及可见性分支。
        List<Map<String, Object>> plan = jdbc.queryForList("""
                EXPLAIN SELECT t.id, t.ticket_no,
                    (SELECT r.content FROM ticket_record r WHERE r.ticket_id = t.id
                     AND r.record_type = 'PROCESS' AND NULLIF(TRIM(r.content), '') IS NOT NULL
                     ORDER BY r.sequence_no DESC LIMIT 1) AS process_content
                FROM ticket t
                WHERE t.category_id = ?
                  AND t.status IN ('COMPLETED', 'CANCELED', 'CLOSED')
                  AND t.ended_at IS NOT NULL AND t.id <> ?
                  AND ((? = TRUE AND t.requester_id = ?)
                    OR (? = TRUE AND (t.assignee_id = ? OR EXISTS (
                        SELECT 1 FROM ticket_participant p
                        WHERE p.ticket_id = t.id AND p.user_id = ?))))
                  AND EXISTS (SELECT 1 FROM ticket_record r WHERE r.ticket_id = t.id
                    AND r.record_type = 'PROCESS' AND NULLIF(TRIM(r.content), '') IS NOT NULL)
                ORDER BY t.ended_at DESC, t.id DESC LIMIT 5
                """, categoryId, ticketId, false, assigneeId,
                true, assigneeId, assigneeId);
        System.out.println("AI_SOURCE_EXPLAIN=" + plan);
        assertThat(plan).filteredOn(row -> "t".equals(row.get("table")))
                .singleElement().satisfies(row -> {
                    assertThat(row.get("type")).isNotEqualTo("ALL");
                    assertThat(row.get("key")).isNotNull();
                });
    }

    /** 每个测试只造虚构来源；来源工单不与目标共享负责人时不应被召回。 */
    private long insertClosedSource(String title, long sourceAssigneeId, String processContent) {
        String sourceNo = "FD-AI-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO ticket (ticket_no, submission_key, requester_id, title, description,
                    category_id, priority, status, assignee_id, close_method, close_reason,
                    ended_at, record_seq, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, '虚构处理过程', ?, 'MEDIUM', 'CLOSED', ?, 'MANUAL', 'INVALID',
                    CURRENT_TIMESTAMP(3), 1, 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """, sourceNo, UUID.randomUUID().toString(), requesterId, title, categoryId,
                sourceAssigneeId);
        long sourceId = jdbc.queryForObject("SELECT id FROM ticket WHERE ticket_no = ?",
                Long.class, sourceNo);
        jdbc.update("""
                INSERT INTO ticket_record (ticket_id, sequence_no, record_type, actor_type,
                    actor_user_id, content, created_at)
                VALUES (?, 1, 'PROCESS', 'USER', ?, ?, CURRENT_TIMESTAMP(3))
                """, sourceId, sourceAssigneeId, processContent);
        return sourceId;
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
