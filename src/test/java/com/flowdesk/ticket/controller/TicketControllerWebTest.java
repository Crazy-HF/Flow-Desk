package com.flowdesk.ticket.controller;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.support.MockedPersistenceConfiguration;
import com.flowdesk.ticket.application.command.AddProcessingRecordCommand;
import com.flowdesk.ticket.application.command.CancelTicketCommand;
import com.flowdesk.ticket.application.command.ChangeCategoryCommand;
import com.flowdesk.ticket.application.command.ChangePriorityCommand;
import com.flowdesk.ticket.application.command.ClaimTicketCommand;
import com.flowdesk.ticket.application.command.CloseTicketCommand;
import com.flowdesk.ticket.application.command.ConfirmResolutionCommand;
import com.flowdesk.ticket.application.command.CreateTicketCommand;
import com.flowdesk.ticket.application.command.ReportUnresolvedCommand;
import com.flowdesk.ticket.application.command.RequestSupplementCommand;
import com.flowdesk.ticket.application.command.SubmitResolutionCommand;
import com.flowdesk.ticket.application.command.SupplementCommand;
import com.flowdesk.ticket.application.command.TransferCommand;
import com.flowdesk.ticket.application.command.WithdrawSupplementRequestCommand;
import com.flowdesk.ticket.application.query.TicketQuery;
import com.flowdesk.ticket.application.query.TicketRecordQuery;
import com.flowdesk.ticket.application.result.TicketActionResult;
import com.flowdesk.ticket.application.result.TicketAssigneeOptionResult;
import com.flowdesk.ticket.application.result.TicketCategorySummaryResult;
import com.flowdesk.ticket.application.result.TicketCreatedResult;
import com.flowdesk.ticket.application.result.TicketDetailResult;
import com.flowdesk.ticket.application.result.TicketListItemResult;
import com.flowdesk.ticket.application.result.TicketRecordResult;
import com.flowdesk.ticket.application.result.TicketUserSummaryResult;
import com.flowdesk.ticket.application.service.TicketQueryService;
import com.flowdesk.ticket.application.service.TicketService;
import com.flowdesk.ticket.domain.TicketStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工单接口的 Web 契约测试（{@code docs/api-design.md} 6.2～6.6）。
 *
 * <p>使用真实安全过滤链与方法级授权，{@link TicketService} 与 {@link TicketQueryService}
 * 都替换为替身：本类只验证 HTTP 状态、响应信封、请求绑定（含 multipart 的 {@code ticket}
 * 部分）、校验失败与权限边界，状态机、幂等与乐观锁规则已由 {@code TicketServiceImplTest}
 * 与 {@code TicketQueryServiceImplTest} 覆盖，此处不重复。</p>
 *
 * <p>权限分层是本类的重点之一：只有列表与创建带方法级 {@code @PreAuthorize}
 * （{@code TICKET_VIEW_*} 按 scope 分支、创建要求 {@code TICKET_CREATE}）；
 * 详情、时间线与四个动作端点把"能否看/能否做"放在服务层，无权与不存在都可能表现为
 * {@code 404/TICKET_NOT_FOUND} 或 {@code 403/TICKET_ACTION_FORBIDDEN}。</p>
 */
@ActiveProfiles("test")
@SpringBootTest(
        classes = FlowDeskApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
@AutoConfigureMockMvc
@Import(MockedPersistenceConfiguration.class)
class TicketControllerWebTest {

    private static final String TICKETS = "/fd/v1/tickets";
    private static final String TICKET_NO = "FD-20261006-0001";
    /** 片 A：两个"从等待态退回处理中"的动作，动作名与接口路径末段逐字一致。 */
    private static final String WITHDRAW_SUPPLEMENT_REQUEST = "withdraw-supplement-request";
    private static final String REPORT_UNRESOLVED = "report-unresolved";
    private static final String REQUEST_SUPPLEMENT = "request-supplement";
    /** 片 C：调整与转交三个动作 + 候选人查询。 */
    private static final String CHANGE_CATEGORY = "change-category";
    private static final String CHANGE_PRIORITY = "change-priority";
    private static final String TRANSFER = "transfer";
    private static final String TRANSFER_CANDIDATES = TICKETS + "/" + TICKET_NO + "/transfer-candidates";
    /** 片 D：结束路径的两个动作。 */
    private static final String CLOSE = "close";
    private static final String CANCEL = "cancel";
    private static final long NEW_ASSIGNEE_ID = 11L;
    private static final String SUBMISSION_KEY = "3f1c2b7e-1d4a-4f2b-9c6e-8a7d5b0c1e2f";
    private static final long CATEGORY_ID = 7L;
    private static final long REQUESTER_ID = 3L;
    private static final long ASSIGNEE_ID = 9L;
    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse("2026-10-06T08:00:00Z");
    private static final OffsetDateTime UPDATED_AT =
            OffsetDateTime.parse("2026-10-06T09:30:00Z");
    private static final OffsetDateTime DEADLINE_AT =
            OffsetDateTime.parse("2026-10-13T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketService ticketService;

    @MockitoBean
    private TicketQueryService ticketQueryService;

    /** 排除 Redis 自动配置后，用替身满足认证模块的依赖。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    // ---------- 认证、鉴权矩阵 ----------

    @ParameterizedTest(name = "{0} without authentication returns 401")
    @MethodSource("ticketRequests")
    void everyEndpointRequiresAuthentication(
            String endpoint, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(ticketService, ticketQueryService);
    }

    /** 十个端点：列表、详情、时间线、创建与六个动作。 */
    static Stream<Arguments> ticketRequests() {
        return Stream.of(
                Arguments.of("list tickets", get(TICKETS)),
                Arguments.of("get ticket", get(TICKETS + "/" + TICKET_NO)),
                Arguments.of("get ticket records", get(TICKETS + "/" + TICKET_NO + "/records")),
                Arguments.of("create ticket", createRequest(validTicketPart())),
                Arguments.of("claim ticket", actionRequest("claim", """
                        {"version": 3}
                        """)),
                Arguments.of("add processing record",
                        actionRequest("add-processing-record", """
                                {"version": 3, "content": "已联系厂商"}
                                """)),
                Arguments.of("submit resolution", actionRequest("submit-resolution", """
                        {"version": 3, "content": "已更换网线"}
                        """)),
                Arguments.of("confirm resolution", actionRequest("confirm-resolution", """
                        {"version": 3}
                        """)),
                Arguments.of("close ticket", actionRequest(CLOSE, validCloseBody())),
                Arguments.of("cancel ticket", actionRequest(CANCEL, """
                        {"version": 3, "reason": "问题已自行解决"}
                        """))
        );
    }

    /**
     * 列表接口的授权按 {@code scope} 分支：每个范围各自要求一个查询权限，
     * 不能用任意一个 {@code TICKET_VIEW_*} 替代（例如只持 {@code TICKET_VIEW_PARTICIPATED}
     * 不能查 {@code PENDING_QUEUE}）。
     */
    @ParameterizedTest(name = "scope {0} is allowed with {1}")
    @MethodSource("scopePermissions")
    void listScopeAllowsHolderOfMatchingPermission(String scope, String authority)
            throws Exception {
        when(ticketQueryService.page(any())).thenReturn(emptyTicketPage());

        mockMvc.perform(get(TICKETS)
                        .with(ticketUser(authority))
                        .queryParam("scope", scope))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.totalElements").value(0));

        verify(ticketQueryService).page(any());
    }

    static Stream<Arguments> scopePermissions() {
        return Stream.of(
                Arguments.of("REQUESTED_BY_ME", "TICKET_VIEW_OWN"),
                Arguments.of("PENDING_QUEUE", "TICKET_VIEW_QUEUE"),
                Arguments.of("ASSIGNED_TO_ME", "TICKET_VIEW_PARTICIPATED"),
                Arguments.of("PARTICIPATED_BY_ME", "TICKET_VIEW_PARTICIPATED"));
    }

    @ParameterizedTest(name = "scope {0} is forbidden with unrelated {1}")
    @MethodSource("scopeDenials")
    void listScopeRejectsUnrelatedPermission(String scope, String grantedAuthority)
            throws Exception {
        mockMvc.perform(get(TICKETS)
                        .with(ticketUser(grantedAuthority))
                        .queryParam("scope", scope))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(ticketQueryService);
    }

    static Stream<Arguments> scopeDenials() {
        return Stream.of(
                Arguments.of("REQUESTED_BY_ME", "TICKET_VIEW_QUEUE"),
                Arguments.of("PENDING_QUEUE", "TICKET_VIEW_PARTICIPATED"),
                Arguments.of("ASSIGNED_TO_ME", "TICKET_VIEW_OWN"),
                Arguments.of("PARTICIPATED_BY_ME", "TICKET_VIEW_QUEUE"));
    }

    /** 创建要求 {@code TICKET_CREATE}；其他工单权限不能替代。 */
    @Test
    void createWithoutTicketCreatePermissionIsForbidden() throws Exception {
        mockMvc.perform(createRequest(validTicketPart())
                        .with(ticketUser("TICKET_VIEW_OWN", "TICKET_PROCESS")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(ticketService, ticketQueryService);
    }

    // ---------- 创建 ----------

    @Test
    void ticketCreatorCanCreateTicketAndMultipartPartReachesService() throws Exception {
        when(ticketService.create(any())).thenReturn(sampleCreated());

        mockMvc.perform(createRequest(validTicketPart()).with(ticketUser("TICKET_CREATE")))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.ticketNo").value(TICKET_NO))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-06T08:00:00Z"));

        ArgumentCaptor<CreateTicketCommand> captor =
                ArgumentCaptor.forClass(CreateTicketCommand.class);
        verify(ticketService).create(captor.capture());
        CreateTicketCommand command = captor.getValue();
        assertThat(command.submissionKey()).isEqualTo(SUBMISSION_KEY);
        // 记录构造器会去掉首尾空白，校验与入库都按去空白后的结果
        assertThat(command.title()).isEqualTo("打印机无法连接");
        assertThat(command.description()).isEqualTo("三楼打印机无法连接网络");
        assertThat(command.categoryId()).isEqualTo(CATEGORY_ID);
        assertThat(command.priority()).isEqualTo("HIGH");
    }

    /**
     * 缺少 {@code ticket} 部分 → {@code 400/VALIDATION_FAILED}。
     *
     * <p>本用例在 2026-10-06 抓出过一处契约不一致：{@code docs/api-design.md} 10.2 要求
     * 请求校验失败返回 {@code 400/VALIDATION_FAILED}，当时实测是 {@code 500/INTERNAL_ERROR}
     * ——Spring 抛的是 {@code MissingServletRequestPartException}，它与
     * {@code MissingServletRequestParameterException} 是兄弟类型，因此没有命中
     * {@code GlobalExceptionHandler.handleBadRequestParameter}，落到了末位 {@code Exception}
     * 兜底分支。经用户授权把该异常登记进 400 处理器后，这里断言契约要求的 400，
     * 同时继续钉住「不泄漏内部细节、不调用服务」。</p>
     */
    @Test
    void missingTicketPartIsRejectedAsValidationFailure() throws Exception {
        String body = mockMvc.perform(multipart(TICKETS).with(ticketUser("TICKET_CREATE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .doesNotContain("Exception")
                .doesNotContain("com.flowdesk")
                .doesNotContain("org.springframework");

        verify(ticketService, never()).create(any());
    }

    @ParameterizedTest(name = "create rejects {0}")
    @MethodSource("invalidTicketParts")
    void createRejectsInvalidTicketPart(
            String caseName, String expectedField, String ticketJson) throws Exception {
        mockMvc.perform(createRequest(ticketJson).with(ticketUser("TICKET_CREATE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem(expectedField)));

        verify(ticketService, never()).create(any());
    }

    static Stream<Arguments> invalidTicketParts() {
        return Stream.of(
                Arguments.of("a submission key that is not a UUID", "submissionKey", """
                        {"submissionKey": "not-a-uuid", "title": "打印机无法连接",
                         "description": "三楼打印机无法连接网络", "categoryId": 7, "priority": "HIGH"}
                        """),
                Arguments.of("a blank title", "title", """
                        {"submissionKey": "%s", "title": "   ",
                         "description": "三楼打印机无法连接网络", "categoryId": 7, "priority": "HIGH"}
                        """.formatted(SUBMISSION_KEY)),
                Arguments.of("a blank description", "description", """
                        {"submissionKey": "%s", "title": "打印机无法连接",
                         "description": "", "categoryId": 7, "priority": "HIGH"}
                        """.formatted(SUBMISSION_KEY)),
                Arguments.of("a missing category", "categoryId", """
                        {"submissionKey": "%s", "title": "打印机无法连接",
                         "description": "三楼打印机无法连接网络", "priority": "HIGH"}
                        """.formatted(SUBMISSION_KEY)),
                Arguments.of("a non positive category", "categoryId", """
                        {"submissionKey": "%s", "title": "打印机无法连接",
                         "description": "三楼打印机无法连接网络", "categoryId": 0, "priority": "HIGH"}
                        """.formatted(SUBMISSION_KEY)),
                Arguments.of("an unknown priority", "priority", """
                        {"submissionKey": "%s", "title": "打印机无法连接",
                         "description": "三楼打印机无法连接网络", "categoryId": 7, "priority": "URGENT"}
                        """.formatted(SUBMISSION_KEY))
        );
    }

    /** 校验失败响应只暴露字段名与约束编码，不含异常类型、内部包名或堆栈。 */
    @Test
    void validationFailureDoesNotLeakInternals() throws Exception {
        String body = mockMvc.perform(createRequest("""
                        {"submissionKey": "not-a-uuid", "title": "打印机无法连接",
                         "description": "三楼打印机无法连接网络", "categoryId": 7, "priority": "HIGH"}
                        """)
                        .with(ticketUser("TICKET_CREATE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body)
                .doesNotContain("Exception")
                .doesNotContain("com.flowdesk")
                .doesNotContain("org.springframework")
                .doesNotContain("\tat ")
                .doesNotContain(".java");
    }

    // ---------- 列表 ----------

    @Test
    void ticketViewerCanListTicketsAndQueryParametersReachService() throws Exception {
        when(ticketQueryService.page(any())).thenReturn(
                new PageResult<>(List.of(sampleListItem()), 2, 50, 1, 1));

        mockMvc.perform(get(TICKETS)
                        .with(ticketUser("TICKET_VIEW_QUEUE"))
                        .queryParam("scope", "PENDING_QUEUE")
                        .queryParam("page", "2")
                        .queryParam("size", "50")
                        .queryParam("status", "PENDING", "PROCESSING")
                        .queryParam("categoryId", String.valueOf(CATEGORY_ID))
                        .queryParam("keyword", "打印机")
                        .queryParam("sort", "PRIORITY_DESC_CREATED_ASC")
                        .queryParam("createdFrom", "2026-10-01T00:00:00Z")
                        .queryParam("createdTo", "2026-10-06T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].ticketNo").value(TICKET_NO))
                .andExpect(jsonPath("$.data.items[0].title").value("打印机无法连接"))
                .andExpect(jsonPath("$.data.items[0].category.id").value(CATEGORY_ID))
                .andExpect(jsonPath("$.data.items[0].priority").value("HIGH"))
                .andExpect(jsonPath("$.data.items[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.items[0].requester.displayName").value("演示员工"))
                // assignee 为 null：全局 non_null 序列化策略下字段整体不出现
                .andExpect(jsonPath("$.data.items[0].assignee").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].version").value(1));

        ArgumentCaptor<TicketQuery> captor = ArgumentCaptor.forClass(TicketQuery.class);
        verify(ticketQueryService).page(captor.capture());
        TicketQuery query = captor.getValue();
        assertThat(query.getScope().name()).isEqualTo("PENDING_QUEUE");
        assertThat(query.getPage()).isEqualTo(2);
        assertThat(query.getSize()).isEqualTo(50);
        assertThat(query.getStatus()).containsExactly(
                TicketStatus.PENDING, TicketStatus.PROCESSING);
        assertThat(query.getCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(query.getKeyword()).isEqualTo("打印机");
        assertThat(query.getSort().name()).isEqualTo("PRIORITY_DESC_CREATED_ASC");
        assertThat(query.getCreatedFrom()).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        assertThat(query.getCreatedTo()).isEqualTo(OffsetDateTime.parse("2026-10-06T00:00:00Z"));
    }

    /** 未指定 sort 时由查询对象给出默认排序，不在 Web 层拼字符串。 */
    @Test
    void listUsesScopeSpecificDefaultSortWhenSortIsMissing() throws Exception {
        when(ticketQueryService.page(any())).thenReturn(emptyTicketPage());

        mockMvc.perform(get(TICKETS)
                        .with(ticketUser("TICKET_VIEW_QUEUE"))
                        .queryParam("scope", "PENDING_QUEUE"))
                .andExpect(status().isOk());

        ArgumentCaptor<TicketQuery> captor = ArgumentCaptor.forClass(TicketQuery.class);
        verify(ticketQueryService).page(captor.capture());
        assertThat(captor.getValue().getSort().name())
                .isEqualTo("PRIORITY_DESC_CREATED_ASC");
        assertThat(captor.getValue().getOrderBy()).isNull();
        assertThat(captor.getValue().getOrderDirection()).isEqualTo("asc");
    }

    /**
     * 排序方向与父类同一口径：大写 {@code ASC} 按升序接受，空的 {@code orderBy}
     * 视为未请求排序，两者都不再被 400 拒绝。
     */
    @Test
    void listAcceptsUppercaseAndBlankSortDirection() throws Exception {
        when(ticketQueryService.page(any())).thenReturn(emptyTicketPage());

        mockMvc.perform(get(TICKETS)
                        .with(ticketUser("TICKET_VIEW_OWN"))
                        .queryParam("scope", "REQUESTED_BY_ME")
                        .queryParam("orderDirection", "ASC"))
                .andExpect(status().isOk());

        mockMvc.perform(get(TICKETS)
                        .with(ticketUser("TICKET_VIEW_OWN"))
                        .queryParam("scope", "REQUESTED_BY_ME")
                        .queryParam("orderBy", ""))
                .andExpect(status().isOk());

        ArgumentCaptor<TicketQuery> captor = ArgumentCaptor.forClass(TicketQuery.class);
        verify(ticketQueryService, times(2)).page(captor.capture());
        assertThat(captor.getAllValues().get(0).getOrderDirection()).isEqualTo("ASC");
        assertThat(captor.getAllValues().get(1).getOrderBy()).isEmpty();
    }

    /** 缺少 scope 时 {@code @NotNull} 先失败，不会因为 SpEL 拿到 null 而报 403。 */
    @Test
    void listWithoutScopeIsRejectedAsValidationFailure() throws Exception {
        mockMvc.perform(get(TICKETS).with(ticketUser("TICKET_VIEW_OWN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem("scope")));

        verifyNoInteractions(ticketQueryService);
    }

    @Test
    void listRejectsPagingOutsideDocumentedBounds() throws Exception {
        mockMvc.perform(get(TICKETS)
                        .with(ticketUser("TICKET_VIEW_OWN"))
                        .queryParam("scope", "REQUESTED_BY_ME")
                        .queryParam("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get(TICKETS)
                        .with(ticketUser("TICKET_VIEW_OWN"))
                        .queryParam("scope", "REQUESTED_BY_ME")
                        .queryParam("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(ticketQueryService);
    }

    /**
     * 工单列表只允许固定 {@code sort} 编码：非空 {@code orderBy} 一律拒绝，
     * {@code orderDirection} 只接受升序且忽略大小写
     * （{@code TicketQuery.isFixedSortOnly} 复用 {@code PageQuery.isAscendingDirection()}）。
     */
    @ParameterizedTest(name = "list rejects {0}")
    @MethodSource("clientSideSorting")
    void listRejectsClientSideSorting(String caseName, String parameter, String value)
            throws Exception {
        mockMvc.perform(get(TICKETS)
                        .with(ticketUser("TICKET_VIEW_OWN"))
                        .queryParam("scope", "REQUESTED_BY_ME")
                        .queryParam(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(ticketQueryService);
    }

    static Stream<Arguments> clientSideSorting() {
        return Stream.of(
                Arguments.of("a column orderBy", "orderBy", "created_at"),
                Arguments.of("an unknown orderDirection", "orderDirection", "sideways"),
                Arguments.of("a descending orderDirection", "orderDirection", "desc"));
    }

    @Test
    void listRejectsCreatedRangeThatRunsBackwards() throws Exception {
        mockMvc.perform(get(TICKETS)
                        .with(ticketUser("TICKET_VIEW_OWN"))
                        .queryParam("scope", "REQUESTED_BY_ME")
                        .queryParam("createdFrom", "2026-10-06T00:00:00Z")
                        .queryParam("createdTo", "2026-10-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem("createdRangeValid")));

        verifyNoInteractions(ticketQueryService);
    }

    @Test
    void listRejectsKeywordOverTwoHundredCharactersAndNonPositiveCategory()
            throws Exception {
        mockMvc.perform(get(TICKETS)
                        .with(ticketUser("TICKET_VIEW_OWN"))
                        .queryParam("scope", "REQUESTED_BY_ME")
                        .queryParam("keyword", "k".repeat(201)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get(TICKETS)
                        .with(ticketUser("TICKET_VIEW_OWN"))
                        .queryParam("scope", "REQUESTED_BY_ME")
                        .queryParam("categoryId", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(ticketQueryService);
    }

    // ---------- 详情与时间线 ----------

    @Test
    void authenticatedUserCanReadTicketDetail() throws Exception {
        when(ticketQueryService.detail(TICKET_NO)).thenReturn(sampleDetail());

        mockMvc.perform(get(TICKETS + "/" + TICKET_NO).with(ticketUser("TICKET_VIEW_OWN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.ticketNo").value(TICKET_NO))
                .andExpect(jsonPath("$.data.description").value("三楼打印机无法连接网络"))
                .andExpect(jsonPath("$.data.category.name").value("办公设备"))
                .andExpect(jsonPath("$.data.requester.id").value(REQUESTER_ID))
                .andExpect(jsonPath("$.data.assignee").doesNotExist())
                .andExpect(jsonPath("$.data.completionMethod").doesNotExist())
                .andExpect(jsonPath("$.data.allowedActions[0]").value("claim"));

        verify(ticketQueryService).detail(TICKET_NO);
    }

    /** 无权查看与确实不存在统一返回 {@code 404/TICKET_NOT_FOUND}。 */
    @Test
    void invisibleOrMissingTicketIsReportedAsNotFound() throws Exception {
        when(ticketQueryService.detail(TICKET_NO))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND",
                        "工单不存在或不可见"));

        mockMvc.perform(get(TICKETS + "/" + TICKET_NO).with(ticketUser("TICKET_VIEW_OWN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"))
                .andExpect(jsonPath("$.data.traceId").exists());
    }

    @Test
    void authenticatedUserCanReadTicketRecords() throws Exception {
        when(ticketQueryService.records(eq(TICKET_NO), any())).thenReturn(
                new PageResult<>(List.of(sampleRecord()), 1, 20, 1, 1));

        mockMvc.perform(get(TICKETS + "/" + TICKET_NO + "/records")
                        .with(ticketUser("TICKET_VIEW_OWN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].sequenceNo").value(1))
                .andExpect(jsonPath("$.data.items[0].recordType").value("CREATED"))
                .andExpect(jsonPath("$.data.items[0].actorType").value("REQUESTER"))
                .andExpect(jsonPath("$.data.items[0].actor.id").value(REQUESTER_ID))
                .andExpect(jsonPath("$.data.items[0].createdAt").value("2026-10-06T08:00:00Z"))
                .andExpect(jsonPath("$.data.items[0].context.title").value("打印机无法连接"));

        ArgumentCaptor<TicketRecordQuery> captor =
                ArgumentCaptor.forClass(TicketRecordQuery.class);
        verify(ticketQueryService).records(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().getOrderBy()).isNull();
        assertThat(captor.getValue().getOrderDirection()).isEqualTo("asc");
    }

    /** 时间线的排序方向同样忽略大小写：{@code ASC} 与默认值等价，仍属固定顺序。 */
    @Test
    void recordsAcceptUppercaseOrderDirection() throws Exception {
        when(ticketQueryService.records(eq(TICKET_NO), any())).thenReturn(
                new PageResult<>(List.of(), 1, 20, 0, 0));

        mockMvc.perform(get(TICKETS + "/" + TICKET_NO + "/records")
                        .with(ticketUser("TICKET_VIEW_OWN"))
                        .queryParam("orderDirection", "ASC"))
                .andExpect(status().isOk());

        ArgumentCaptor<TicketRecordQuery> captor =
                ArgumentCaptor.forClass(TicketRecordQuery.class);
        verify(ticketQueryService).records(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().getOrderDirection()).isEqualTo("ASC");
    }

    /** 时间线顺序固定为记录序号升序，客户端排序参数一律拒绝。 */
    @Test
    void recordsRejectClientSideOrdering() throws Exception {
        mockMvc.perform(get(TICKETS + "/" + TICKET_NO + "/records")
                        .with(ticketUser("TICKET_VIEW_OWN"))
                        .queryParam("orderBy", "sequence_no"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get(TICKETS + "/" + TICKET_NO + "/records")
                        .with(ticketUser("TICKET_VIEW_OWN"))
                        .queryParam("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(ticketQueryService);
    }

    // ---------- 四个动作 ----------

    @Test
    void claimReturnsTicketActionEnvelope() throws Exception {
        when(ticketService.claim(eq(TICKET_NO), any())).thenReturn(sampleAction());

        assertActionEnvelope(mockMvc.perform(
                actionRequest("claim", versionOnlyBody()).with(ticketUser("TICKET_CLAIM"))));

        ArgumentCaptor<ClaimTicketCommand> captor =
                ArgumentCaptor.forClass(ClaimTicketCommand.class);
        verify(ticketService).claim(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(3L);
    }

    @Test
    void addProcessingRecordReturnsTicketActionEnvelope() throws Exception {
        when(ticketService.addProcessingRecord(eq(TICKET_NO), any()))
                .thenReturn(sampleAction());

        assertActionEnvelope(mockMvc.perform(actionRequest("add-processing-record", """
                        {"version": 3, "content": "  已联系厂商  "}
                        """).with(ticketUser("TICKET_PROCESS"))));

        ArgumentCaptor<AddProcessingRecordCommand> captor =
                ArgumentCaptor.forClass(AddProcessingRecordCommand.class);
        verify(ticketService).addProcessingRecord(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(3L);
        assertThat(captor.getValue().content()).isEqualTo("已联系厂商");
    }

    @Test
    void submitResolutionReturnsTicketActionEnvelope() throws Exception {
        when(ticketService.submitResolution(eq(TICKET_NO), any()))
                .thenReturn(sampleAction());

        assertActionEnvelope(mockMvc.perform(actionRequest("submit-resolution", """
                        {"version": 3, "content": "已更换网线"}
                        """).with(ticketUser("TICKET_PROCESS"))));

        ArgumentCaptor<SubmitResolutionCommand> captor =
                ArgumentCaptor.forClass(SubmitResolutionCommand.class);
        verify(ticketService).submitResolution(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().content()).isEqualTo("已更换网线");
    }

    @Test
    void confirmResolutionReturnsTicketActionEnvelope() throws Exception {
        when(ticketService.confirmResolution(eq(TICKET_NO), any()))
                .thenReturn(sampleAction());

        assertActionEnvelope(mockMvc.perform(actionRequest("confirm-resolution",
                versionOnlyBody()).with(ticketUser("TICKET_REQUESTER_ACTION"))));

        ArgumentCaptor<ConfirmResolutionCommand> captor =
                ArgumentCaptor.forClass(ConfirmResolutionCommand.class);
        verify(ticketService).confirmResolution(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(3L);
    }

    @ParameterizedTest(name = "{0} without version is rejected")
    @MethodSource("versionlessActions")
    void actionsRejectMissingVersion(String action, MockHttpServletRequestBuilder request)
            throws Exception {
        mockMvc.perform(request.with(ticketUser("TICKET_CLAIM")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem("version")));

        verifyNoInteractions(ticketService);
    }

    static Stream<Arguments> versionlessActions() {
        return Stream.of(
                Arguments.of("claim", actionRequest("claim", "{}")),
                Arguments.of("add-processing-record",
                        actionRequest("add-processing-record", """
                                {"content": "已联系厂商"}
                                """)),
                Arguments.of("submit-resolution",
                        actionRequest("submit-resolution", """
                                {"content": "已更换网线"}
                                """)),
                Arguments.of("confirm-resolution", actionRequest("confirm-resolution", "{}"))
        );
    }

    /** 版本不能为负数：{@code @PositiveOrZero} 拒绝明显不可能的快照版本。 */
    @Test
    void actionsRejectNegativeVersion() throws Exception {
        mockMvc.perform(actionRequest("claim", """
                        {"version": -1}
                        """).with(ticketUser("TICKET_CLAIM")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(actionRequest("add-processing-record", """
                        {"version": -1, "content": "已联系厂商"}
                        """).with(ticketUser("TICKET_PROCESS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(actionRequest("submit-resolution", """
                        {"version": -1, "content": "已更换网线"}
                        """).with(ticketUser("TICKET_PROCESS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(actionRequest("confirm-resolution", """
                        {"version": -1}
                        """).with(ticketUser("TICKET_REQUESTER_ACTION")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(ticketService);
    }

    /** 处理正文与解决结论：去空白后必填，且最长 10000 个字符。 */
    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("invalidActionBodies")
    void contentActionsRejectBlankAndOverlongContent(
            String action, String caseName, String requestBody) throws Exception {
        mockMvc.perform(actionRequest(action, requestBody).with(ticketUser("TICKET_PROCESS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem("content")));

        verifyNoInteractions(ticketService);
    }

    static Stream<Arguments> invalidActionBodies() {
        String tooLong = "x".repeat(10001);
        return Stream.of(
                Arguments.of("add-processing-record", "blank content", """
                        {"version": 3, "content": "   "}
                        """),
                Arguments.of("add-processing-record", "content over 10000 characters", """
                        {"version": 3, "content": "%s"}
                        """.formatted(tooLong)),
                Arguments.of("submit-resolution", "blank content", """
                        {"version": 3, "content": ""}
                        """),
                Arguments.of("submit-resolution", "content over 10000 characters", """
                        {"version": 3, "content": "%s"}
                        """.formatted(tooLong))
        );
    }

    /** 乐观锁失败时 {@code data} 必须带当前快照，前端据此刷新而不是盲目重放。 */
    @Test
    void staleVersionConflictCarriesCurrentSnapshot() throws Exception {
        when(ticketService.claim(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "TICKET_CONFLICT",
                        "工单状态已变化", 5L, "WAITING_FOR_CONFIRMATION"));

        mockMvc.perform(actionRequest("claim", versionOnlyBody())
                        .with(ticketUser("TICKET_CLAIM")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_CONFLICT"))
                .andExpect(jsonPath("$.data.traceId").exists())
                .andExpect(jsonPath("$.data.version").value(5))
                .andExpect(jsonPath("$.data.status").value("WAITING_FOR_CONFIRMATION"))
                .andExpect(jsonPath("$.data.fieldErrors").doesNotExist());
    }

    /** 能看但无权做：{@code 403/TICKET_ACTION_FORBIDDEN} 不是过滤器链的 {@code ACCESS_DENIED}。 */
    @Test
    void actionForbiddenByTicketRelationshipIsReportedAsActionForbidden() throws Exception {
        when(ticketService.claim(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN",
                        "当前身份或工单状态不允许该动作"));

        mockMvc.perform(actionRequest("claim", versionOnlyBody())
                        .with(ticketUser("TICKET_CLAIM")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TICKET_ACTION_FORBIDDEN"));
    }

    /** 服务返回 {@code null} 时信封里不应出现 {@code data} 字段（全局 non_null 策略）。 */
    @Test
    void nullResultOmitsDataField() throws Exception {
        mockMvc.perform(actionRequest("claim", versionOnlyBody())
                        .with(ticketUser("TICKET_CLAIM")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ---------- 片 A：两个退回处理中的动作 ----------

    /**
     * 两个新端点与其他动作端点同一口径：都不带方法级 {@code @PreAuthorize}，
     * 权限落在服务层，"能做与不能做"由 {@code 403/TICKET_ACTION_FORBIDDEN} 表达；
     * 无令牌则在过滤器链就被拦下，不进入服务。
     */
    @ParameterizedTest(name = "{0} without authentication returns 401")
    @MethodSource("returnActionCases")
    void returnActionsRequireAuthentication(String action, String requestBody) throws Exception {
        mockMvc.perform(actionRequest(action, requestBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(ticketService, ticketQueryService);
    }

    /** 员工侧权限调撤回：服务层拒，错误码必须是动作级 {@code TICKET_ACTION_FORBIDDEN}。 */
    @Test
    void withdrawSupplementRequestIsForbiddenForEmployeeAuthorities() throws Exception {
        stubReturnActionFailure(WITHDRAW_SUPPLEMENT_REQUEST,
                new ApiException(HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN",
                        "无处理工单权限"));

        mockMvc.perform(actionRequest(WITHDRAW_SUPPLEMENT_REQUEST, """
                        {"version": 5, "reason": "提交人已补齐信息"}
                        """).with(ticketUser("TICKET_CREATE", "TICKET_VIEW_OWN",
                        "TICKET_REQUESTER_ACTION")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TICKET_ACTION_FORBIDDEN"));

        verify(ticketService).withdrawSupplementRequest(eq(TICKET_NO), any());
    }

    /** IT 与管理员侧权限调反馈未解决：同样由服务层返回 403，而不是接口不存在。 */
    @Test
    void reportUnresolvedIsForbiddenForItAndAdminAuthorities() throws Exception {
        stubReturnActionFailure(REPORT_UNRESOLVED,
                new ApiException(HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN",
                        "无提交人操作权限"));

        String body = """
                {"version": 4, "reason": "问题又出现了"}
                """;
        mockMvc.perform(actionRequest(REPORT_UNRESOLVED, body)
                        .with(ticketUser("TICKET_PROCESS", "TICKET_VIEW_PARTICIPATED")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TICKET_ACTION_FORBIDDEN"));

        mockMvc.perform(actionRequest(REPORT_UNRESOLVED, body)
                        .with(ticketUser("TICKET_ADMIN_HANDOFF", "USER_MANAGE", "CATEGORY_MANAGE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TICKET_ACTION_FORBIDDEN"));

        verify(ticketService, times(2)).reportUnresolved(eq(TICKET_NO), any());
    }

    /** 版本、原因两类字段校验：都没到服务层就被 Bean Validation 拦下。 */
    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("invalidReturnActionBodies")
    void returnActionsRejectInvalidBody(
            String action, String caseName, String requestBody, String expectedField)
            throws Exception {
        mockMvc.perform(actionRequest(action, requestBody).with(ticketUser("TICKET_PROCESS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem(expectedField)));

        verifyNoInteractions(ticketService);
    }

    static Stream<Arguments> invalidReturnActionBodies() {
        String reasonOverLimit = "x".repeat(1001);
        return Stream.of(
                Arguments.of(WITHDRAW_SUPPLEMENT_REQUEST, "a missing version", """
                        {"reason": "提交人已补齐信息"}
                        """, "version"),
                Arguments.of(REPORT_UNRESOLVED, "a missing version", """
                        {"reason": "问题又出现了"}
                        """, "version"),
                Arguments.of(WITHDRAW_SUPPLEMENT_REQUEST, "a negative version", """
                        {"version": -1, "reason": "提交人已补齐信息"}
                        """, "version"),
                Arguments.of(REPORT_UNRESOLVED, "a negative version", """
                        {"version": -1, "reason": "问题又出现了"}
                        """, "version"),
                Arguments.of(WITHDRAW_SUPPLEMENT_REQUEST, "a blank reason", """
                        {"version": 5, "reason": "   "}
                        """, "reason"),
                Arguments.of(REPORT_UNRESOLVED, "a blank reason", """
                        {"version": 4, "reason": ""}
                        """, "reason"),
                Arguments.of(REPORT_UNRESOLVED, "a missing reason", """
                        {"version": 4}
                        """, "reason"),
                Arguments.of(WITHDRAW_SUPPLEMENT_REQUEST,
                        "a reason over 1000 characters", """
                        {"version": 5, "reason": "%s"}
                        """.formatted(reasonOverLimit), "reason")
        );
    }

    /** 乐观锁失败：{@code data} 必须带当前快照，前端据此刷新而不是盲目重放。 */
    @ParameterizedTest(name = "{0} conflict carries the current snapshot")
    @MethodSource("returnActionCases")
    void returnActionConflictCarriesCurrentSnapshot(String action, String requestBody)
            throws Exception {
        stubReturnActionFailure(action,
                new ApiException(HttpStatus.CONFLICT, "TICKET_CONFLICT",
                        "工单状态或版本已变化，请刷新后重试", 6L, "PROCESSING"));

        mockMvc.perform(actionRequest(action, requestBody)
                        .with(ticketUser("TICKET_PROCESS", "TICKET_REQUESTER_ACTION")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_CONFLICT"))
                .andExpect(jsonPath("$.data.traceId").exists())
                .andExpect(jsonPath("$.data.version").value(6))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.fieldErrors").doesNotExist());
    }

    /** 无权查看与编号不存在统一 {@code 404/TICKET_NOT_FOUND}。 */
    @ParameterizedTest(name = "{0} reports not found when the ticket is invisible")
    @MethodSource("returnActionCases")
    void returnActionReportsNotFoundWhenTicketIsInvisible(String action, String requestBody)
            throws Exception {
        stubReturnActionFailure(action,
                new ApiException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "工单不存在"));

        mockMvc.perform(actionRequest(action, requestBody)
                        .with(ticketUser("TICKET_PROCESS", "TICKET_REQUESTER_ACTION")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"))
                .andExpect(jsonPath("$.data.traceId").exists());
    }

    @Test
    void withdrawSupplementRequestReturnsTicketActionEnvelope() throws Exception {
        when(ticketService.withdrawSupplementRequest(eq(TICKET_NO), any()))
                .thenReturn(processingAction());

        assertReturnActionEnvelope(mockMvc.perform(
                actionRequest(WITHDRAW_SUPPLEMENT_REQUEST, """
                                {"version": 5, "reason": "  提交人已补齐信息  "}
                                """).with(ticketUser("TICKET_PROCESS"))));

        ArgumentCaptor<WithdrawSupplementRequestCommand> captor =
                ArgumentCaptor.forClass(WithdrawSupplementRequestCommand.class);
        verify(ticketService).withdrawSupplementRequest(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(5L);
        assertThat(captor.getValue().reason()).as("原因在命令构造器中去除了首尾空白")
                .isEqualTo("提交人已补齐信息");
    }

    @Test
    void reportUnresolvedReturnsTicketActionEnvelope() throws Exception {
        when(ticketService.reportUnresolved(eq(TICKET_NO), any()))
                .thenReturn(processingAction());

        assertReturnActionEnvelope(mockMvc.perform(
                actionRequest(REPORT_UNRESOLVED, """
                                {"version": 4, "reason": "  问题又出现了  "}
                                """).with(ticketUser("TICKET_REQUESTER_ACTION"))));

        ArgumentCaptor<ReportUnresolvedCommand> captor =
                ArgumentCaptor.forClass(ReportUnresolvedCommand.class);
        verify(ticketService).reportUnresolved(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(4L);
        assertThat(captor.getValue().reason()).isEqualTo("问题又出现了");
    }

    // ---------- 片 B：补充往返 ----------

    /**
     * {@code request-supplement} 是 JSON 端点，与其他 JSON 动作同一口径：
     * 无令牌 401、权限由服务层给出 {@code 403/TICKET_ACTION_FORBIDDEN}。
     */
    @Test
    void requestSupplementRequiresAuthentication() throws Exception {
        mockMvc.perform(actionRequest(REQUEST_SUPPLEMENT, """
                        {"version": 3, "content": "请补充打印机型号"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(ticketService, ticketQueryService);
    }

    @Test
    void requestSupplementIsForbiddenForEmployeeAuthorities() throws Exception {
        when(ticketService.requestSupplement(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN",
                        "无处理工单权限"));

        mockMvc.perform(actionRequest(REQUEST_SUPPLEMENT, """
                        {"version": 3, "content": "请补充打印机型号"}
                        """).with(ticketUser("TICKET_CREATE", "TICKET_VIEW_OWN",
                        "TICKET_REQUESTER_ACTION")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TICKET_ACTION_FORBIDDEN"));

        verify(ticketService).requestSupplement(eq(TICKET_NO), any());
    }

    /** 版本、正文两类字段校验：都没到服务层就被 Bean Validation 拦下。 */
    @ParameterizedTest(name = "request-supplement rejects {0}")
    @MethodSource("invalidRequestSupplementBodies")
    void requestSupplementRejectsInvalidBody(String caseName, String requestBody, String field)
            throws Exception {
        mockMvc.perform(actionRequest(REQUEST_SUPPLEMENT, requestBody)
                        .with(ticketUser("TICKET_PROCESS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem(field)));

        verifyNoInteractions(ticketService);
    }

    static Stream<Arguments> invalidRequestSupplementBodies() {
        return Stream.of(
                Arguments.of("a missing version", """
                        {"content": "请补充打印机型号"}
                        """, "version"),
                Arguments.of("a negative version", """
                        {"version": -1, "content": "请补充打印机型号"}
                        """, "version"),
                Arguments.of("a blank content", """
                        {"version": 3, "content": "   "}
                        """, "content"),
                Arguments.of("a missing content", """
                        {"version": 3}
                        """, "content"),
                Arguments.of("content over 10000 characters", """
                        {"version": 3, "content": "%s"}
                        """.formatted("补".repeat(10001)), "content"));
    }

    /** 冲突与不可见沿用同一错误契约：409 带当前快照，404 不区分"无权"与"不存在"。 */
    @Test
    void requestSupplementConflictCarriesCurrentSnapshot() throws Exception {
        when(ticketService.requestSupplement(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "TICKET_CONFLICT",
                        "工单状态或版本已变化，请刷新后重试", 6L, "PROCESSING"));

        mockMvc.perform(actionRequest(REQUEST_SUPPLEMENT, """
                        {"version": 3, "content": "请补充打印机型号"}
                        """).with(ticketUser("TICKET_PROCESS")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_CONFLICT"))
                .andExpect(jsonPath("$.data.version").value(6))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }

    @Test
    void requestSupplementReportsNotFoundWhenTicketIsInvisible() throws Exception {
        when(ticketService.requestSupplement(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "工单不存在"));

        mockMvc.perform(actionRequest(REQUEST_SUPPLEMENT, """
                        {"version": 3, "content": "请补充打印机型号"}
                        """).with(ticketUser("TICKET_PROCESS")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"))
                .andExpect(jsonPath("$.data.traceId").exists());
    }

    /** 请求补充成功：进入「待补充」，响应带回服务端计算的补充期限。 */
    @Test
    void requestSupplementReturnsTicketActionEnvelopeWithDeadline() throws Exception {
        when(ticketService.requestSupplement(eq(TICKET_NO), any()))
                .thenReturn(waitingForRequesterAction());

        mockMvc.perform(actionRequest(REQUEST_SUPPLEMENT, """
                        {"version": 3, "content": "  请补充打印机型号与错误截图  "}
                        """).with(ticketUser("TICKET_PROCESS")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.ticketNo").value(TICKET_NO))
                .andExpect(jsonPath("$.data.status").value("WAITING_FOR_REQUESTER"))
                .andExpect(jsonPath("$.data.assignee.id").value(ASSIGNEE_ID))
                .andExpect(jsonPath("$.data.actionDeadlineAt").value("2026-10-13T08:00:00Z"))
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.actionTime").value("2026-10-06T08:00:00Z"));

        ArgumentCaptor<RequestSupplementCommand> captor =
                ArgumentCaptor.forClass(RequestSupplementCommand.class);
        verify(ticketService).requestSupplement(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(3L);
        assertThat(captor.getValue().content()).as("正文在命令构造器中去除了首尾空白")
                .isEqualTo("请补充打印机型号与错误截图");
    }

    @Test
    void supplementRequiresAuthentication() throws Exception {
        mockMvc.perform(supplementRequest("""
                        {"version": 5, "content": "型号是 M404dn"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(ticketService, ticketQueryService);
    }

    /**
     * 缺少 {@code ticket} 部分要按 10.2 的调用方输入错误返回 400，
     * 不能回落 500——这条与创建工单缺 part 是同一处登记（{@code MissingServletRequestPartException}）。
     */
    @Test
    void supplementWithoutTicketPartIsRejectedAsValidationFailure() throws Exception {
        String body = mockMvc.perform(multipart(TICKETS + "/" + TICKET_NO + "/actions/supplement")
                        .with(ticketUser("TICKET_REQUESTER_ACTION")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).as("不泄露内部类名或堆栈").doesNotContain("Exception", "at com.flowdesk");
        verifyNoInteractions(ticketService);
    }

    /**
     * 本版本只接受正文：带文件部分必须返回 400 并说明不支持附件，**不能静默忽略**——
     * 静默忽略会让调用方以为附件已经收下（`docs/api-design.md` 6.4 的范围裁决）。
     *
     * <p>两个 part 都在同一次 {@code multipart(...)} 里给出：先构造再认证，
     * 才能保证"已认证 + 带文件"这个组合本身被测到。</p>
     */
    @Test
    void supplementRejectsFilePartWithExplicitValidationFailure() throws Exception {
        mockMvc.perform(multipart(TICKETS + "/" + TICKET_NO + "/actions/supplement")
                        .file(ticketPart("""
                                {"version": 5, "content": "型号是 M404dn"}
                                """))
                        .file(filePart("screenshot.png", "fake-image-bytes"))
                        .with(ticketUser("TICKET_REQUESTER_ACTION")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message", containsString("本版本不支持附件")));

        verifyNoInteractions(ticketService);
    }

    /** 空文件部分同样按"出现了文件"处理：判定的依据是 part 存在，不是字节数为正。 */
    @Test
    void supplementRejectsEmptyFilePartToo() throws Exception {
        mockMvc.perform(multipart(TICKETS + "/" + TICKET_NO + "/actions/supplement")
                        .file(ticketPart("""
                                {"version": 5, "content": "型号是 M404dn"}
                                """))
                        .file(filePart("empty.txt", ""))
                        .with(ticketUser("TICKET_REQUESTER_ACTION")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(ticketService);
    }

    @ParameterizedTest(name = "supplement rejects {0}")
    @MethodSource("invalidSupplementBodies")
    void supplementRejectsInvalidTicketPart(String caseName, String ticketJson, String field)
            throws Exception {
        mockMvc.perform(supplementRequest(ticketJson).with(ticketUser("TICKET_REQUESTER_ACTION")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem(field)));

        verifyNoInteractions(ticketService);
    }

    static Stream<Arguments> invalidSupplementBodies() {
        return Stream.of(
                Arguments.of("a missing version", """
                        {"content": "型号是 M404dn"}
                        """, "version"),
                Arguments.of("a blank content", """
                        {"version": 5, "content": "   "}
                        """, "content"),
                Arguments.of("content over 10000 characters", """
                        {"version": 5, "content": "%s"}
                        """.formatted("补".repeat(10001)), "content"));
    }

    /** multipart 的 {@code ticket} 部分必须原样到达服务层，并被反序列化成命令对象。 */
    @Test
    void supplementReturnsTicketActionEnvelopeAndReachesService() throws Exception {
        when(ticketService.supplement(eq(TICKET_NO), any())).thenReturn(processingAction());

        assertReturnActionEnvelope(mockMvc.perform(supplementRequest("""
                        {"version": 5, "content": "  型号是 M404dn  "}
                        """).with(ticketUser("TICKET_REQUESTER_ACTION"))));

        ArgumentCaptor<SupplementCommand> captor =
                ArgumentCaptor.forClass(SupplementCommand.class);
        verify(ticketService).supplement(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(5L);
        assertThat(captor.getValue().content()).isEqualTo("型号是 M404dn");
    }

    @Test
    void supplementIsForbiddenForItAuthorities() throws Exception {
        when(ticketService.supplement(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN",
                        "无提交人操作权限"));

        mockMvc.perform(supplementRequest("""
                        {"version": 5, "content": "型号是 M404dn"}
                        """).with(ticketUser("TICKET_PROCESS", "TICKET_VIEW_PARTICIPATED")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TICKET_ACTION_FORBIDDEN"));

        verify(ticketService).supplement(eq(TICKET_NO), any());
    }

    // ---------- 片 C：调整与转交 ----------

    /**
     * 三个动作与候选人接口都是 JSON/GET 端点，与其他端点同一口径：
     * 无令牌一律被认证闸门挡下，权限与身份判定由服务层给出稳定错误码。
     */
    @Test
    void adjustAndTransferEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(actionRequest(CHANGE_CATEGORY, """
                        {"version": 3, "categoryId": 7, "reason": "分类选错了"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(actionRequest(CHANGE_PRIORITY, """
                        {"version": 3, "priority": "LOW", "reason": "影响面缩小"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(actionRequest(TRANSFER, """
                        {"version": 3, "newAssigneeId": 11, "reason": "换人跟进"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get(TRANSFER_CANDIDATES))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(ticketService, ticketQueryService);
    }

    /** 转交与调整走两条不同的权限码：{@code TICKET_PROCESS} 不覆盖 {@code TICKET_TRANSFER}。 */
    @Test
    void transferIsForbiddenWithoutTransferAuthority() throws Exception {
        when(ticketService.transfer(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN",
                        "无转交工单权限"));

        mockMvc.perform(actionRequest(TRANSFER, """
                        {"version": 3, "newAssigneeId": 11, "reason": "换人跟进"}
                        """).with(ticketUser("TICKET_PROCESS")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TICKET_ACTION_FORBIDDEN"));

        verify(ticketService).transfer(eq(TICKET_NO), any());
    }

    /** 版本、分类与说明三类字段校验：都没到服务层就被 Bean Validation 拦下。 */
    @ParameterizedTest(name = "change-category rejects {0}")
    @MethodSource("invalidChangeCategoryBodies")
    void changeCategoryRejectsInvalidBody(String caseName, String requestBody, String field)
            throws Exception {
        mockMvc.perform(actionRequest(CHANGE_CATEGORY, requestBody)
                        .with(ticketUser("TICKET_PROCESS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem(field)));

        verifyNoInteractions(ticketService);
    }

    static Stream<Arguments> invalidChangeCategoryBodies() {
        String tooLongReason = "x".repeat(1001);
        return Stream.of(
                Arguments.of("a missing version", """
                        {"categoryId": 7, "reason": "分类选错了"}
                        """, "version"),
                Arguments.of("a negative version", """
                        {"version": -1, "categoryId": 7, "reason": "分类选错了"}
                        """, "version"),
                Arguments.of("a missing category", """
                        {"version": 3, "reason": "分类选错了"}
                        """, "categoryId"),
                Arguments.of("a non positive category", """
                        {"version": 3, "categoryId": 0, "reason": "分类选错了"}
                        """, "categoryId"),
                Arguments.of("a blank reason", """
                        {"version": 3, "categoryId": 7, "reason": "   "}
                        """, "reason"),
                Arguments.of("a reason over 1000 characters", """
                        {"version": 3, "categoryId": 7, "reason": "%s"}
                        """.formatted(tooLongReason), "reason")
        );
    }

    @Test
    void changeCategoryReturnsTicketActionEnvelope() throws Exception {
        when(ticketService.changeCategory(eq(TICKET_NO), any()))
                .thenReturn(processingAction());

        assertReturnActionEnvelope(mockMvc.perform(actionRequest(CHANGE_CATEGORY, """
                        {"version": 4, "categoryId": 7, "reason": "  分类选错了  "}
                        """).with(ticketUser("TICKET_PROCESS"))));

        ArgumentCaptor<ChangeCategoryCommand> captor =
                ArgumentCaptor.forClass(ChangeCategoryCommand.class);
        verify(ticketService).changeCategory(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(4L);
        assertThat(captor.getValue().categoryId()).isEqualTo(CATEGORY_ID);
        assertThat(captor.getValue().reason()).as("说明在命令构造器中去除了首尾空白")
                .isEqualTo("分类选错了");
    }

    /** 接口契约要求"不存在或不可见"统一 404，且响应里带 {@code traceId} 便于定位。 */
    @Test
    void changeCategoryReportsNotFoundWhenTicketIsInvisible() throws Exception {
        when(ticketService.changeCategory(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "工单不存在"));

        mockMvc.perform(actionRequest(CHANGE_CATEGORY, """
                        {"version": 4, "categoryId": 7, "reason": "分类选错了"}
                        """).with(ticketUser("TICKET_PROCESS")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"))
                .andExpect(jsonPath("$.data.traceId").exists());
    }

    @Test
    void changeCategoryConflictCarriesCurrentSnapshot() throws Exception {
        when(ticketService.changeCategory(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "TICKET_CONFLICT",
                        "工单状态或负责人已变化", 5L, "WAITING_FOR_REQUESTER"));

        mockMvc.perform(actionRequest(CHANGE_CATEGORY, """
                        {"version": 4, "categoryId": 7, "reason": "分类选错了"}
                        """).with(ticketUser("TICKET_PROCESS")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_CONFLICT"))
                .andExpect(jsonPath("$.data.version").value(5))
                .andExpect(jsonPath("$.data.status").value("WAITING_FOR_REQUESTER"));
    }

    @ParameterizedTest(name = "change-priority rejects {0}")
    @MethodSource("invalidChangePriorityBodies")
    void changePriorityRejectsInvalidBody(String caseName, String requestBody, String field)
            throws Exception {
        mockMvc.perform(actionRequest(CHANGE_PRIORITY, requestBody)
                        .with(ticketUser("TICKET_PROCESS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem(field)));

        verifyNoInteractions(ticketService);
    }

    static Stream<Arguments> invalidChangePriorityBodies() {
        return Stream.of(
                Arguments.of("a missing version", """
                        {"priority": "LOW", "reason": "影响面缩小"}
                        """, "version"),
                Arguments.of("a missing priority", """
                        {"version": 3, "reason": "影响面缩小"}
                        """, "priority"),
                Arguments.of("a lowercase priority", """
                        {"version": 3, "priority": "low", "reason": "影响面缩小"}
                        """, "priority"),
                Arguments.of("an unknown priority", """
                        {"version": 3, "priority": "URGENT", "reason": "影响面缩小"}
                        """, "priority"),
                Arguments.of("a blank reason", """
                        {"version": 3, "priority": "LOW", "reason": "   "}
                        """, "reason")
        );
    }

    @Test
    void changePriorityReturnsTicketActionEnvelope() throws Exception {
        when(ticketService.changePriority(eq(TICKET_NO), any()))
                .thenReturn(processingAction());

        assertReturnActionEnvelope(mockMvc.perform(actionRequest(CHANGE_PRIORITY, """
                        {"version": 4, "priority": "LOW", "reason": "  影响面缩小  "}
                        """).with(ticketUser("TICKET_PROCESS"))));

        ArgumentCaptor<ChangePriorityCommand> captor =
                ArgumentCaptor.forClass(ChangePriorityCommand.class);
        verify(ticketService).changePriority(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().priority()).isEqualTo("LOW");
        assertThat(captor.getValue().reason()).isEqualTo("影响面缩小");
    }

    @ParameterizedTest(name = "transfer rejects {0}")
    @MethodSource("invalidTransferBodies")
    void transferRejectsInvalidBody(String caseName, String requestBody, String field)
            throws Exception {
        mockMvc.perform(actionRequest(TRANSFER, requestBody)
                        .with(ticketUser("TICKET_TRANSFER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem(field)));

        verifyNoInteractions(ticketService);
    }

    static Stream<Arguments> invalidTransferBodies() {
        String tooLongReason = "x".repeat(1001);
        return Stream.of(
                Arguments.of("a missing version", """
                        {"newAssigneeId": 11, "reason": "换人跟进"}
                        """, "version"),
                Arguments.of("a missing new assignee", """
                        {"version": 3, "reason": "换人跟进"}
                        """, "newAssigneeId"),
                Arguments.of("a non positive new assignee", """
                        {"version": 3, "newAssigneeId": 0, "reason": "换人跟进"}
                        """, "newAssigneeId"),
                Arguments.of("a blank reason", """
                        {"version": 3, "newAssigneeId": 11, "reason": "  "}
                        """, "reason"),
                Arguments.of("a reason over 1000 characters", """
                        {"version": 3, "newAssigneeId": 11, "reason": "%s"}
                        """.formatted(tooLongReason), "reason")
        );
    }

    @Test
    void transferReturnsTicketActionEnvelopeWithNewAssignee() throws Exception {
        when(ticketService.transfer(eq(TICKET_NO), any())).thenReturn(new TicketActionResult(
                TICKET_NO,
                "PROCESSING",
                new TicketUserSummaryResult(NEW_ASSIGNEE_ID, "演示 IT 三号"),
                null,
                5L,
                CREATED_AT));

        mockMvc.perform(actionRequest(TRANSFER, """
                        {"version": 4, "newAssigneeId": 11, "reason": "  换人跟进  "}
                        """).with(ticketUser("TICKET_TRANSFER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.assignee.id").value(NEW_ASSIGNEE_ID))
                .andExpect(jsonPath("$.data.assignee.displayName").value("演示 IT 三号"))
                .andExpect(jsonPath("$.data.actionDeadlineAt").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(5));

        ArgumentCaptor<TransferCommand> captor = ArgumentCaptor.forClass(TransferCommand.class);
        verify(ticketService).transfer(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().newAssigneeId()).isEqualTo(NEW_ASSIGNEE_ID);
        assertThat(captor.getValue().reason()).isEqualTo("换人跟进");
    }

    /** 候选人接口只回两个字段，且不使用分页信封。 */
    @Test
    void transferCandidatesReturnsOptionList() throws Exception {
        when(ticketQueryService.transferCandidates(TICKET_NO)).thenReturn(List.of(
                new TicketAssigneeOptionResult(NEW_ASSIGNEE_ID, "演示 IT 三号")));

        mockMvc.perform(get(TRANSFER_CANDIDATES).with(ticketUser("TICKET_TRANSFER")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data[0].id").value(NEW_ASSIGNEE_ID))
                .andExpect(jsonPath("$.data[0].displayName").value("演示 IT 三号"))
                .andExpect(jsonPath("$.data[0].username").doesNotExist());

        verify(ticketQueryService).transferCandidates(TICKET_NO);
    }

    /** 没有候选人时是空数组：前端据此渲染空态，而不是把它当成错误。 */
    @Test
    void transferCandidatesReturnsEmptyArrayWhenNobodyIsEligible() throws Exception {
        when(ticketQueryService.transferCandidates(TICKET_NO)).thenReturn(List.of());

        mockMvc.perform(get(TRANSFER_CANDIDATES).with(ticketUser("TICKET_TRANSFER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    /** 状态或负责人不符时按冲突返回，并把当前快照交给前端刷新。 */
    @Test
    void transferCandidatesConflictCarriesCurrentSnapshot() throws Exception {
        when(ticketQueryService.transferCandidates(TICKET_NO))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "TICKET_CONFLICT",
                        "工单状态或负责人已变化", 6L, "PENDING"));

        mockMvc.perform(get(TRANSFER_CANDIDATES).with(ticketUser("TICKET_TRANSFER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_CONFLICT"))
                .andExpect(jsonPath("$.data.version").value(6))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void transferCandidatesReportsNotFoundWhenTicketIsInvisible() throws Exception {
        when(ticketQueryService.transferCandidates(TICKET_NO))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "工单不存在"));

        mockMvc.perform(get(TRANSFER_CANDIDATES).with(ticketUser("TICKET_TRANSFER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"))
                .andExpect(jsonPath("$.data.traceId").exists());
    }

    // ---------- 片 D：结束路径 ----------

    /**
     * 关闭的字段校验：版本、原因码、说明与重复单号长度都在进服务层之前被 Bean Validation 拦下。
     *
     * <p>{@code REQUESTER_NO_RESPONSE} 是系统自动关闭的原因码，人工接口明确不接受；
     * 它与 {@code duplicate}（小写）都由 {@code @Pattern} 挡住。</p>
     */
    @ParameterizedTest(name = "close rejects {0}")
    @MethodSource("invalidCloseBodies")
    void closeRejectsInvalidBody(String caseName, String requestBody, String field)
            throws Exception {
        mockMvc.perform(actionRequest(CLOSE, requestBody)
                        .with(ticketUser("TICKET_PROCESS", "TICKET_CLOSE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem(field)));

        verifyNoInteractions(ticketService);
    }

    static Stream<Arguments> invalidCloseBodies() {
        String tooLongDescription = "x".repeat(1001);
        String tooLongTicketNo = "F".repeat(33);
        return Stream.of(
                Arguments.of("a missing version", """
                        {"reasonCode": "OUT_OF_SCOPE", "description": "超出支持范围"}
                        """, "version"),
                Arguments.of("a negative version", """
                        {"version": -1, "reasonCode": "OUT_OF_SCOPE", "description": "超出支持范围"}
                        """, "version"),
                Arguments.of("a missing reason code", """
                        {"version": 4, "description": "超出支持范围"}
                        """, "reasonCode"),
                Arguments.of("the automatic close reason", """
                        {"version": 4, "reasonCode": "REQUESTER_NO_RESPONSE", "description": "逾期未补充"}
                        """, "reasonCode"),
                Arguments.of("a lowercase reason code", """
                        {"version": 4, "reasonCode": "duplicate", "description": "与另一张单重复"}
                        """, "reasonCode"),
                Arguments.of("a blank description", """
                        {"version": 4, "reasonCode": "INVALID", "description": "   "}
                        """, "description"),
                Arguments.of("a description over 1000 characters", """
                        {"version": 4, "reasonCode": "INVALID", "description": "%s"}
                        """.formatted(tooLongDescription), "description"),
                Arguments.of("a duplicate ticket number over 32 characters", """
                        {"version": 4, "reasonCode": "DUPLICATE", "description": "与另一张单重复",
                         "duplicateTicketNo": "%s"}
                        """.formatted(tooLongTicketNo), "duplicateTicketNo")
        );
    }

    /** 关闭成功的信封：终态没有期限，字段整体不出现（全局 {@code non_null} 策略）。 */
    @Test
    void closeReturnsTicketActionEnvelope() throws Exception {
        when(ticketService.close(eq(TICKET_NO), any())).thenReturn(closedAction());

        mockMvc.perform(actionRequest(CLOSE, """
                        {"version": 4, "reasonCode": "OUT_OF_SCOPE", "description": "  超出支持范围  ",
                         "duplicateTicketNo": "   "}
                        """).with(ticketUser("TICKET_PROCESS", "TICKET_CLOSE")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.ticketNo").value(TICKET_NO))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.assignee.id").value(ASSIGNEE_ID))
                .andExpect(jsonPath("$.data.actionDeadlineAt").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(5));

        ArgumentCaptor<CloseTicketCommand> captor = ArgumentCaptor.forClass(CloseTicketCommand.class);
        verify(ticketService).close(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(4L);
        assertThat(captor.getValue().reasonCode()).isEqualTo("OUT_OF_SCOPE");
        assertThat(captor.getValue().description()).as("说明在命令构造器中去除了首尾空白")
                .isEqualTo("超出支持范围");
        assertThat(captor.getValue().duplicateTicketNo())
                .as("纯空白的重复单号等于没传，不该变成「非重复原因却带了单号」的 400")
                .isNull();
    }

    /** 重复关闭：单号去空白后原样进入命令，由服务层解析目标并写关联。 */
    @Test
    void closePassesStrippedDuplicateTicketNoToService() throws Exception {
        when(ticketService.close(eq(TICKET_NO), any())).thenReturn(closedAction());

        mockMvc.perform(actionRequest(CLOSE, """
                        {"version": 4, "reasonCode": "DUPLICATE", "description": "与另一张单重复",
                         "duplicateTicketNo": "  FD-20261006-0002  "}
                        """).with(ticketUser("TICKET_PROCESS", "TICKET_CLOSE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        ArgumentCaptor<CloseTicketCommand> captor = ArgumentCaptor.forClass(CloseTicketCommand.class);
        verify(ticketService).close(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().duplicateTicketNo()).isEqualTo("FD-20261006-0002");
    }

    /**
     * 关闭要求 {@code TICKET_PROCESS} 与 {@code TICKET_CLOSE} 同时成立
     * （2026-10-08 用户裁决），任一缺失都由服务层给出同一个 403 编码。
     */
    @Test
    void closeIsForbiddenWithoutBothAuthorities() throws Exception {
        when(ticketService.close(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN",
                        "无关闭工单权限"));

        mockMvc.perform(actionRequest(CLOSE, validCloseBody())
                        .with(ticketUser("TICKET_PROCESS")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TICKET_ACTION_FORBIDDEN"));

        verify(ticketService).close(eq(TICKET_NO), any());
    }

    @Test
    void closeConflictCarriesCurrentSnapshot() throws Exception {
        when(ticketService.close(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "TICKET_CONFLICT",
                        "工单状态或版本已变化", 6L, "WAITING_FOR_CONFIRMATION"));

        mockMvc.perform(actionRequest(CLOSE, validCloseBody())
                        .with(ticketUser("TICKET_PROCESS", "TICKET_CLOSE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_CONFLICT"))
                .andExpect(jsonPath("$.data.version").value(6))
                .andExpect(jsonPath("$.data.status").value("WAITING_FOR_CONFIRMATION"));
    }

    @Test
    void closeReportsNotFoundWhenTicketIsInvisible() throws Exception {
        when(ticketService.close(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "工单不存在"));

        mockMvc.perform(actionRequest(CLOSE, validCloseBody())
                        .with(ticketUser("TICKET_PROCESS", "TICKET_CLOSE")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"))
                .andExpect(jsonPath("$.data.traceId").exists());
    }

    /** 跨字段规则（非重复原因带单号）由服务层判定：Web 层原样透传 400。 */
    @Test
    void closePassesCrossFieldValidationFailureThrough() throws Exception {
        when(ticketService.close(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                        "只有关闭原因为重复工单时才能填写重复工单编号"));

        mockMvc.perform(actionRequest(CLOSE, """
                        {"version": 4, "reasonCode": "INVALID", "description": "无效工单",
                         "duplicateTicketNo": "FD-20261006-0002"}
                        """).with(ticketUser("TICKET_PROCESS", "TICKET_CLOSE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @ParameterizedTest(name = "cancel rejects {0}")
    @MethodSource("invalidCancelBodies")
    void cancelRejectsInvalidBody(String caseName, String requestBody, String field)
            throws Exception {
        mockMvc.perform(actionRequest(CANCEL, requestBody)
                        .with(ticketUser("TICKET_REQUESTER_ACTION")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem(field)));

        verifyNoInteractions(ticketService);
    }

    static Stream<Arguments> invalidCancelBodies() {
        String tooLongReason = "x".repeat(1001);
        return Stream.of(
                Arguments.of("a missing version", """
                        {"reason": "问题已自行解决"}
                        """, "version"),
                Arguments.of("a negative version", """
                        {"version": -1, "reason": "问题已自行解决"}
                        """, "version"),
                Arguments.of("a missing reason", """
                        {"version": 4}
                        """, "reason"),
                Arguments.of("a blank reason", """
                        {"version": 4, "reason": "   "}
                        """, "reason"),
                Arguments.of("a reason over 1000 characters", """
                        {"version": 4, "reason": "%s"}
                        """.formatted(tooLongReason), "reason")
        );
    }

    /** 撤销成功的信封：终态、期限消失、说明去空白。 */
    @Test
    void cancelReturnsTicketActionEnvelope() throws Exception {
        when(ticketService.cancel(eq(TICKET_NO), any())).thenReturn(canceledAction());

        mockMvc.perform(actionRequest(CANCEL, """
                        {"version": 4, "reason": "  问题已自行解决  "}
                        """).with(ticketUser("TICKET_REQUESTER_ACTION")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.ticketNo").value(TICKET_NO))
                .andExpect(jsonPath("$.data.status").value("CANCELED"))
                .andExpect(jsonPath("$.data.assignee.id").value(ASSIGNEE_ID))
                .andExpect(jsonPath("$.data.actionDeadlineAt").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(5));

        ArgumentCaptor<CancelTicketCommand> captor = ArgumentCaptor.forClass(CancelTicketCommand.class);
        verify(ticketService).cancel(eq(TICKET_NO), captor.capture());
        assertThat(captor.getValue().version()).isEqualTo(4L);
        assertThat(captor.getValue().reason()).as("原因在命令构造器中去除了首尾空白")
                .isEqualTo("问题已自行解决");
    }

    /**
     * 撤销的 403 由服务层给出：端点没有方法级 {@code @PreAuthorize}，已认证但缺权限的账号
     * 会一路走到服务层，再由它给出 {@code TICKET_ACTION_FORBIDDEN}——
     * 这样"无权"与"不存在"才可能共用同一个 404 口径。
     */
    @Test
    void cancelIsForbiddenWithoutRequesterActionAuthority() throws Exception {
        when(ticketService.cancel(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN",
                        "无提交人操作权限"));

        mockMvc.perform(actionRequest(CANCEL, """
                        {"version": 4, "reason": "问题已自行解决"}
                        """).with(ticketUser("TICKET_VIEW_OWN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TICKET_ACTION_FORBIDDEN"));
    }

    @Test
    void cancelConflictCarriesCurrentSnapshot() throws Exception {
        when(ticketService.cancel(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "TICKET_CONFLICT",
                        "工单状态或版本已变化", 6L, "COMPLETED"));

        mockMvc.perform(actionRequest(CANCEL, """
                        {"version": 4, "reason": "问题已自行解决"}
                        """).with(ticketUser("TICKET_REQUESTER_ACTION")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TICKET_CONFLICT"))
                .andExpect(jsonPath("$.data.version").value(6))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void cancelReportsNotFoundWhenTicketIsInvisible() throws Exception {
        when(ticketService.cancel(eq(TICKET_NO), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "工单不存在"));

        mockMvc.perform(actionRequest(CANCEL, """
                        {"version": 4, "reason": "问题已自行解决"}
                        """).with(ticketUser("TICKET_REQUESTER_ACTION")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"))
                .andExpect(jsonPath("$.data.traceId").exists());
    }

    // ---------- 辅助 ----------

    /** 片 B 两个动作的成功信封：请求补充进入「待补充」，提交补充回到「处理中」。 */
    private static TicketActionResult waitingForRequesterAction() {
        return new TicketActionResult(
                TICKET_NO,
                "WAITING_FOR_REQUESTER",
                new TicketUserSummaryResult(ASSIGNEE_ID, "演示 IT 支持人员"),
                CREATED_AT.plusSeconds(7 * 24 * 3600),
                4L,
                CREATED_AT);
    }

    private static MockMultipartHttpServletRequestBuilder supplementRequest(String ticketJson) {
        return multipart(TICKETS + "/" + TICKET_NO + "/actions/supplement")
                .file(ticketPart(ticketJson));
    }

    /** multipart 里的 JSON 命令部分：部件必须自带 {@code application/json}，否则后端无法反序列化。 */
    private static MockMultipartFile ticketPart(String ticketJson) {
        return new MockMultipartFile(
                "ticket", "ticket.json", MediaType.APPLICATION_JSON_VALUE,
                ticketJson.getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile filePart(String fileName, String fileContent) {
        return new MockMultipartFile(
                "files", fileName, MediaType.IMAGE_PNG_VALUE,
                fileContent.getBytes(StandardCharsets.UTF_8));
    }

    /** 片 A 两个动作的入参：动作名 + 各自动作的合法请求体。 */
    static Stream<Arguments> returnActionCases() {
        return Stream.of(
                Arguments.of(WITHDRAW_SUPPLEMENT_REQUEST, """
                        {"version": 5, "reason": "提交人已补齐信息"}
                        """),
                Arguments.of(REPORT_UNRESOLVED, """
                        {"version": 4, "reason": "问题又出现了"}
                        """));
    }

    /**
     * 片 A 的成功信封：回到 {@code PROCESSING}，期限为 {@code null}
     * ——全局 {@code non_null} 序列化策略下字段整体不出现。
     */
    private void assertReturnActionEnvelope(ResultActions result) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.ticketNo").value(TICKET_NO))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.assignee.id").value(ASSIGNEE_ID))
                .andExpect(jsonPath("$.data.assignee.displayName").value("演示 IT 支持人员"))
                .andExpect(jsonPath("$.data.actionDeadlineAt").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.actionTime").value("2026-10-06T08:00:00Z"));
    }

    private static TicketActionResult processingAction() {
        return new TicketActionResult(
                TICKET_NO,
                "PROCESSING",
                new TicketUserSummaryResult(ASSIGNEE_ID, "演示 IT 支持人员"),
                null,
                4L,
                CREATED_AT);
    }

    /** 片 D 两个结束动作的成功信封：终态、无期限、负责人保留。 */
    private static TicketActionResult closedAction() {
        return endedAction("CLOSED");
    }

    private static TicketActionResult canceledAction() {
        return endedAction("CANCELED");
    }

    private static TicketActionResult endedAction(String status) {
        return new TicketActionResult(
                TICKET_NO,
                status,
                new TicketUserSummaryResult(ASSIGNEE_ID, "演示 IT 支持人员"),
                null,
                5L,
                CREATED_AT);
    }

    private static String validCloseBody() {
        return """
                {"version": 4, "reasonCode": "OUT_OF_SCOPE", "description": "超出支持范围"}
                """;
    }

    /** 片 A 两个动作的入口不同，参数化用例按动作名分派替身。 */
    private void stubReturnActionFailure(String action, RuntimeException failure) {
        switch (action) {
            case WITHDRAW_SUPPLEMENT_REQUEST -> when(
                    ticketService.withdrawSupplementRequest(eq(TICKET_NO), any()))
                    .thenThrow(failure);
            case REPORT_UNRESOLVED -> when(
                    ticketService.reportUnresolved(eq(TICKET_NO), any()))
                    .thenThrow(failure);
            default -> throw new IllegalArgumentException("未知动作：" + action);
        }
    }

    private void assertActionEnvelope(ResultActions result) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.ticketNo").value(TICKET_NO))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.assignee.id").value(ASSIGNEE_ID))
                .andExpect(jsonPath("$.data.assignee.displayName").value("演示 IT 支持人员"))
                .andExpect(jsonPath("$.data.actionDeadlineAt").value("2026-10-13T08:00:00Z"))
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.actionTime").value("2026-10-06T08:00:00Z"));
    }

    private static MockMultipartHttpServletRequestBuilder createRequest(String ticketJson) {
        return multipart(TICKETS).file(new MockMultipartFile(
                "ticket", "ticket.json", MediaType.APPLICATION_JSON_VALUE,
                ticketJson.getBytes(StandardCharsets.UTF_8)));
    }

    private static MockHttpServletRequestBuilder actionRequest(String action, String body) {
        return post(TICKETS + "/" + TICKET_NO + "/actions/" + action)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static String validTicketPart() {
        return """
                {
                  "submissionKey": "%s",
                  "title": "  打印机无法连接  ",
                  "description": "  三楼打印机无法连接网络  ",
                  "categoryId": 7,
                  "priority": "HIGH"
                }
                """.formatted(SUBMISSION_KEY);
    }

    private static String versionOnlyBody() {
        return """
                {"version": 3}
                """;
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor
    ticketUser(String... authorities) {
        return user("ticket-user").authorities(Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList());
    }

    private static PageResult<TicketListItemResult> emptyTicketPage() {
        return new PageResult<>(List.of(), 1, 20, 0, 0);
    }

    private static TicketCreatedResult sampleCreated() {
        return new TicketCreatedResult(TICKET_NO, "PENDING", 1L, CREATED_AT);
    }

    private static TicketListItemResult sampleListItem() {
        return new TicketListItemResult(
                TICKET_NO,
                "打印机无法连接",
                new TicketCategorySummaryResult(CATEGORY_ID, "办公设备"),
                "HIGH",
                "PENDING",
                new TicketUserSummaryResult(REQUESTER_ID, "演示员工"),
                null,
                null,
                CREATED_AT,
                UPDATED_AT,
                1L);
    }

    private static TicketDetailResult sampleDetail() {
        return new TicketDetailResult(
                TICKET_NO,
                "打印机无法连接",
                "三楼打印机无法连接网络",
                new TicketCategorySummaryResult(CATEGORY_ID, "办公设备"),
                "HIGH",
                "PENDING",
                new TicketUserSummaryResult(REQUESTER_ID, "演示员工"),
                null,
                null,
                1L,
                null,
                null,
                null,
                null,
                CREATED_AT,
                UPDATED_AT,
                List.of("claim"));
    }

    private static TicketRecordResult sampleRecord() {
        return new TicketRecordResult(
                1,
                "CREATED",
                "REQUESTER",
                new TicketUserSummaryResult(REQUESTER_ID, "演示员工"),
                CREATED_AT,
                Map.of("title", "打印机无法连接"));
    }

    private static TicketActionResult sampleAction() {
        return new TicketActionResult(
                TICKET_NO,
                "PROCESSING",
                new TicketUserSummaryResult(ASSIGNEE_ID, "演示 IT 支持人员"),
                DEADLINE_AT,
                4L,
                CREATED_AT);
    }
}
