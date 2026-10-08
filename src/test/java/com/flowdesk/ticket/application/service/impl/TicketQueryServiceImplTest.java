package com.flowdesk.ticket.application.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.ticket.application.port.CurrentRequesterPort;
import com.flowdesk.ticket.application.port.TicketClaimantPort;
import com.flowdesk.ticket.application.port.TicketReadPermissionPort;
import com.flowdesk.ticket.application.query.TicketQuery;
import com.flowdesk.ticket.application.query.TicketRecordQuery;
import com.flowdesk.ticket.application.result.TicketAssigneeOptionResult;
import com.flowdesk.ticket.application.result.TicketCategorySummaryResult;
import com.flowdesk.ticket.application.result.TicketDetailResult;
import com.flowdesk.ticket.application.result.TicketListItemResult;
import com.flowdesk.ticket.application.result.TicketRecordResult;
import com.flowdesk.ticket.application.result.TicketUserSummaryResult;
import com.flowdesk.ticket.domain.TicketScope;
import com.flowdesk.ticket.infrastructure.persistence.TicketAssigneeRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketDetailRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketListRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketRecordRow;
import com.flowdesk.ticket.mapper.TicketMapper;
import com.flowdesk.ticket.mapper.TicketRecordMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工单查询服务的业务规则：列表的数据范围/关键词/时间下发口径、详情按钮提示
 * （{@code allowedActions}）的逐格判定与终态收口、时间线的可见性闸门，以及按记录类型
 * 白名单裁剪的时间线 context。
 *
 * <p>契约见 {@code docs/api-design.md} 6.2 与 6.3。其中
 * {@link #detailAllowsProcessingAndResolutionActionsForAssigneeInFixedOrder()} 是 2026-10-06
 * 修复过的缺陷的回归守卫：{@code PROCESSING} 状态下当前负责人必须同时拿到
 * {@code add-processing-record} 与 {@code submit-resolution}，且顺序固定——前端按
 * {@code allowedActions} 渲染按钮，两项缺一就等于负责人交不出解决结果。</p>
 *
 * <p>片 A 新增两格：{@code WAITING_FOR_REQUESTER} + 本人是负责人 + {@code TICKET_PROCESS}
 * → {@code withdraw-supplement-request}；{@code WAITING_FOR_CONFIRMATION} + 本人是提交人 +
 * {@code TICKET_REQUESTER_ACTION} → {@code confirm-resolution} 与 {@code report-unresolved}
 * 同时出现（装配顺序以前者在前）。后一格的期望值由原来的单动作改为两个动作——
 * {@code canReportUnresolved} 直接复用 {@code canConfirm}，旧断言编码的是片 A 之前的实现。</p>
 *
 * <p>片 D 再新增两格：{@code close}（复用 {@code canProcess} 的身份判定，再叠
 * {@code TICKET_CLOSE}，只允许「处理中」）与 {@code cancel}（四种非终态 + 本人是提交人 +
 * {@code TICKET_REQUESTER_ACTION}）。两格共用权限码的地方有意不同：关闭要两条授权，
 * 撤销只要提交人那一条。</p>
 */
@ExtendWith(MockitoExtension.class)
// 多个用例共享"当前用户 + 权限 + 可见性行"替身，未使用的桩不应判定为失败。
@MockitoSettings(strictness = Strictness.LENIENT)
class TicketQueryServiceImplTest {

    private static final long REQUESTER_ID = 42L;
    private static final long IT_USER_ID = 7L;
    private static final long OTHER_IT_USER_ID = 9L;
    private static final long NEW_IT_USER_ID = 11L;
    private static final long TICKET_ID = 1001L;
    private static final long CATEGORY_ID = 3L;

    private static final String TICKET_NO = "FD-20261006-001";
    private static final String REQUESTER_DISPLAY_NAME = "演示员工";
    private static final String IT_DISPLAY_NAME = "演示 IT 支持人员";
    private static final String NEW_IT_DISPLAY_NAME = "演示 IT 三号";
    private static final String PENDING = "PENDING";
    private static final String PROCESSING = "PROCESSING";
    private static final String WAITING_FOR_REQUESTER = "WAITING_FOR_REQUESTER";
    private static final String WAITING_FOR_CONFIRMATION = "WAITING_FOR_CONFIRMATION";
    private static final String COMPLETED = "COMPLETED";
    private static final String CANCELED = "CANCELED";
    private static final String CLOSED = "CLOSED";
    private static final String HIGH = "HIGH";

    /** 时间线记录里所有可映射字段都用非空值，任何"多出来的键"都会被白名单断言抓住。 */
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 6, 1, 30, 15);
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 10, 6, 2, 0, 0);
    private static final LocalDateTime DEADLINE_AT = LocalDateTime.of(2026, 10, 13, 1, 30, 15);
    private static final LocalDateTime ENDED_AT = LocalDateTime.of(2026, 10, 6, 3, 0, 0);

    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private CurrentRequesterPort currentRequesterPort;
    @Mock
    private TicketReadPermissionPort ticketReadPermissionPort;
    @Mock
    private TicketRecordMapper ticketRecordMapper;
    @Mock
    private TicketClaimantPort ticketClaimantPort;

    private TicketQueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TicketQueryServiceImpl(
                ticketMapper,
                currentRequesterPort,
                ticketReadPermissionPort,
                ticketRecordMapper,
                ticketClaimantPort);
    }

    // ---------- page ----------

    /** scope 是数据范围的唯一来源，缺失时不能在没有任何范围约束的情况下查库。 */
    @Test
    void pageRejectsMissingScopeBeforeAnyQuery() {
        TicketQuery query = new TicketQuery();

        assertApiException(() -> service.page(query),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketMapper, ticketRecordMapper, currentRequesterPort);
    }

    @Test
    void pagePassesAuthenticatedUserAndRequestedPagingToMapper() {
        TicketQuery query = new TicketQuery();
        query.setScope(TicketScope.REQUESTED_BY_ME);
        query.setPage(3);
        query.setSize(7);
        stubCurrentUser(REQUESTER_ID);
        stubScopedPage(List.of(), 0);

        service.page(query);

        ArgumentCaptor<Page<TicketListRow>> pageCaptor = pageCaptor();
        verify(ticketMapper).selectScopedPage(
                pageCaptor.capture(), eq(REQUESTER_ID), eq(query), isNull(), isNull(), isNull());
        assertThat(pageCaptor.getValue().getCurrent()).as("页码来自 query.current").isEqualTo(3L);
        assertThat(pageCaptor.getValue().getSize()).as("每页条数来自 query.size").isEqualTo(7L);
    }

    /**
     * 关键词先去掉首尾空白，再按 {@code !} → {@code %} → {@code _} 的顺序转义
     * （{@code !} 必须最先替换，否则会把后面插入的转义符再转义一次），最后包上两侧通配符。
     */
    @Test
    void pageEscapesLikeSpecialCharactersInKeyword() {
        TicketQuery query = new TicketQuery();
        query.setScope(TicketScope.REQUESTED_BY_ME);
        query.setKeyword(" 50%_off! ");
        stubCurrentUser(REQUESTER_ID);
        stubScopedPage(List.of(), 0);

        service.page(query);

        ArgumentCaptor<String> keywordCaptor = ArgumentCaptor.forClass(String.class);
        verify(ticketMapper).selectScopedPage(
                any(), anyLong(), any(), keywordCaptor.capture(), any(), any());
        assertThat(keywordCaptor.getValue())
                .as("去空白后 ! → !!、% → !%、_ → !_，再包通配符")
                .isEqualTo("%50!%!_off!!%");
    }

    @Test
    void pageOmitsKeywordPatternWhenKeywordIsNull() {
        TicketQuery query = new TicketQuery();
        query.setScope(TicketScope.REQUESTED_BY_ME);
        stubCurrentUser(REQUESTER_ID);
        stubScopedPage(List.of(), 0);

        service.page(query);

        verify(ticketMapper).selectScopedPage(
                any(), anyLong(), any(), isNull(), isNull(), isNull());
    }

    @Test
    void pageOmitsKeywordPatternWhenKeywordIsBlank() {
        TicketQuery query = new TicketQuery();
        query.setScope(TicketScope.REQUESTED_BY_ME);
        query.setKeyword("   ");
        stubCurrentUser(REQUESTER_ID);
        stubScopedPage(List.of(), 0);

        service.page(query);

        verify(ticketMapper).selectScopedPage(
                any(), anyLong(), any(), isNull(), isNull(), isNull());
    }

    /** 带时区的入参必须换算成同一时刻的 UTC 本地时间，否则边界会整体偏移 8 小时。 */
    @Test
    void pageConvertsCreationBoundsToTheSameInstantInUtc() {
        TicketQuery query = new TicketQuery();
        query.setScope(TicketScope.REQUESTED_BY_ME);
        query.setCreatedFrom(OffsetDateTime.parse("2026-10-06T08:00:00+08:00"));
        query.setCreatedTo(OffsetDateTime.parse("2026-10-07T09:30:00+09:00"));
        stubCurrentUser(REQUESTER_ID);
        stubScopedPage(List.of(), 0);

        service.page(query);

        ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(ticketMapper).selectScopedPage(
                any(), anyLong(), any(), any(), fromCaptor.capture(), toCaptor.capture());
        assertThat(fromCaptor.getValue()).as("+08:00 的 08:00 就是 UTC 的 00:00")
                .isEqualTo(LocalDateTime.of(2026, 10, 6, 0, 0));
        assertThat(toCaptor.getValue()).as("+09:00 的 09:30 就是 UTC 的 00:30")
                .isEqualTo(LocalDateTime.of(2026, 10, 7, 0, 30));
    }

    @Test
    void pageMapsRowsIntoItemsAndKeepsMapperPageEnvelope() {
        stubCurrentUser(REQUESTER_ID);
        stubScopedPage(List.of(listRow()), 45);

        PageResult<TicketListItemResult> result = service.page(requestedByMeQuery());

        assertThat(result.page()).as("页码来自 Mapper 返回的 Page").isEqualTo(1);
        assertThat(result.size()).as("每页条数来自 Mapper 返回的 Page").isEqualTo(20);
        assertThat(result.totalElements()).as("总数来自 Mapper 返回的 Page").isEqualTo(45);
        assertThat(result.totalPages()).as("45 条按每页 20 条应折成 3 页").isEqualTo(3);

        assertThat(result.items()).hasSize(1);
        TicketListItemResult item = result.items().get(0);
        assertThat(item.ticketNo()).isEqualTo(TICKET_NO);
        assertThat(item.title()).isEqualTo("打印机无法连接");
        assertThat(item.category()).as("分类摘要来自 JOIN 出来的分类行")
                .isEqualTo(new TicketCategorySummaryResult(CATEGORY_ID, "办公设备"));
        assertThat(item.priority()).isEqualTo(HIGH);
        assertThat(item.status()).isEqualTo(PENDING);
        assertThat(item.requester()).as("提交人摘要来自 JOIN 出来的提交人行")
                .isEqualTo(new TicketUserSummaryResult(REQUESTER_ID, REQUESTER_DISPLAY_NAME));
        assertThat(item.assignee()).as("负责人摘要来自 JOIN 出来的负责人行")
                .isEqualTo(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        assertThat(item.actionDeadlineAt()).isEqualTo(DEADLINE_AT.atOffset(ZoneOffset.UTC));
        assertThat(item.createdAt()).isEqualTo(CREATED_AT.atOffset(ZoneOffset.UTC));
        assertThat(item.updatedAt()).isEqualTo(UPDATED_AT.atOffset(ZoneOffset.UTC));
        assertThat(item.version()).as("版本原样透传，客户端据此做乐观锁").isEqualTo(3L);
    }

    @Test
    void pageReturnsNullAssigneeSummaryWhenRowHasNoAssignee() {
        TicketListRow row = listRow();
        row.setAssigneeId(null);
        row.setAssigneeDisplayName(null);
        stubCurrentUser(REQUESTER_ID);
        stubScopedPage(List.of(row), 1);

        PageResult<TicketListItemResult> result = service.page(requestedByMeQuery());

        assertThat(result.items().get(0).assignee())
                .as("没有负责人时摘要为 null，而不是 id/名称为空的空对象")
                .isNull();
    }

    // ---------- detail + allowedActions ----------

    /** 无权与不存在共用一个响应：替身返回 null 时必须 404，且三个可见性开关按权限原样下发。 */
    @Test
    void detailReportsNotFoundWhenTicketIsNotVisible() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_VIEW_OWN", "TICKET_VIEW_QUEUE");
        when(ticketMapper.selectVisibleDetail(TICKET_NO, IT_USER_ID, true, true, false))
                .thenReturn(null);

        assertApiException(() -> service.detail(TICKET_NO),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verify(ticketMapper).selectVisibleDetail(TICKET_NO, IT_USER_ID, true, true, false);
    }

    @Test
    void detailAllowsClaimForEligibleClaimantOfPendingUnassignedTicket() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_VIEW_QUEUE", "TICKET_CLAIM");
        when(ticketClaimantPort.isEligibleClaimant(IT_USER_ID)).thenReturn(true);
        stubVisible(detailRow(PENDING, null, 0L));

        TicketDetailResult result = service.detail(TICKET_NO);

        assertActions(result, "claim");
    }

    @Test
    void detailHidesClaimWithoutClaimAuthority() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_VIEW_QUEUE");
        when(ticketClaimantPort.isEligibleClaimant(IT_USER_ID)).thenReturn(true);
        stubVisible(detailRow(PENDING, null, 0L));

        assertActions(service.detail(TICKET_NO));
    }

    @Test
    void detailHidesClaimWithoutQueueViewAuthority() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_CLAIM");
        when(ticketClaimantPort.isEligibleClaimant(IT_USER_ID)).thenReturn(true);
        stubVisible(detailRow(PENDING, null, 0L));

        assertActions(service.detail(TICKET_NO));
    }

    /** 有权限但不是"仍启用且持有 IT_SUPPORT 角色"的领取人时，按钮不能提示可领取。 */
    @Test
    void detailHidesClaimForIneligibleClaimant() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_VIEW_QUEUE", "TICKET_CLAIM");
        when(ticketClaimantPort.isEligibleClaimant(IT_USER_ID)).thenReturn(false);
        stubVisible(detailRow(PENDING, null, 0L));

        assertActions(service.detail(TICKET_NO));
    }

    /** 提交人不能领取自己提交的工单：即使它是无人负责的待处理工单。 */
    @Test
    void detailHidesClaimForRequesterOfOwnTicket() {
        stubCurrentUser(REQUESTER_ID);
        grant("TICKET_VIEW_QUEUE", "TICKET_CLAIM");
        when(ticketClaimantPort.isEligibleClaimant(REQUESTER_ID)).thenReturn(true);
        stubVisible(detailRow(PENDING, null, 0L));

        assertActions(service.detail(TICKET_NO));
    }

    /**
     * 2026-10-06 缺陷回归：{@code PROCESSING} 的当前负责人必须同时拿到追加处理记录、提交解决结果
     * 、请求补充，以及片 C 的分类与优先级调整。
     *
     * <p>动作顺序固定；{@code submit-resolution} 曾经缺失，导致负责人能写处理记录却交不出
     * 解决结果——界面按 {@code allowedActions} 渲染按钮，缺一项就等于该动作不可达。
     * {@code request-supplement} 是片 B 新增的同一族判定（处理中 + 本人是负责人 + {@code TICKET_PROCESS}），
     * 片 C 的 {@code change-category} 与 {@code change-priority} 属于同一族，因此本用例的期望值
     * 从三条扩到五条。{@code transfer} 不在这里：它单独要求 {@code TICKET_TRANSFER}，本用例没有授予。</p>
     */
    @Test
    void detailAllowsProcessingAndResolutionActionsForAssigneeInFixedOrder() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_PROCESS");
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        TicketDetailResult result = service.detail(TICKET_NO);

        assertThat(result.allowedActions())
                .as("负责人拿到的五个动作，顺序固定且 submit-resolution 必须在")
                .containsExactly(
                        "add-processing-record", "submit-resolution", "request-supplement",
                        "change-category", "change-priority");
    }

    @Test
    void detailHidesProcessingActionsWithoutProcessAuthority() {
        stubCurrentUser(IT_USER_ID);
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertActions(service.detail(TICKET_NO));
    }

    @Test
    void detailHidesProcessingActionsForNonAssignee() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_PROCESS");
        stubVisible(detailRow(PROCESSING, OTHER_IT_USER_ID, 3L));

        assertActions(service.detail(TICKET_NO));
    }

    @Test
    void detailHidesProcessingActionsWhenTicketHasNoAssignee() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_PROCESS");
        stubVisible(detailRow(PROCESSING, null, 3L));

        assertActions(service.detail(TICKET_NO));
    }

    /**
     * 片 A 契约：{@code WAITING_FOR_CONFIRMATION} 的提交人同时拿到确认与反馈未解决两格。
     *
     * <p>两者前置条件完全相同（待确认 + 本人是提交人 + {@code TICKET_REQUESTER_ACTION}），
     * 装配顺序固定为 {@code confirm-resolution} 在前；{@code report-unresolved} 是片 A 新增的判定，
     * 因此本用例的期望值从单个动作改为一对——旧断言编码的是片 A 之前的实现。</p>
     *
     * <p>片 D 又在这张表上加了第三格 {@code cancel}：撤销在四种非终态上都可用，与确认、
     * 反馈未解决共用同一个权限码但条件更宽，因此它排在最后。</p>
     */
    @Test
    void detailAllowsConfirmAndReportUnresolvedForRequesterInFixedOrder() {
        stubCurrentUser(REQUESTER_ID);
        grant("TICKET_REQUESTER_ACTION");
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 4L));

        TicketDetailResult result = service.detail(TICKET_NO);

        assertThat(result.allowedActions())
                .as("提交人在待确认上有两个互斥选择，外加随时可用的撤销，顺序固定且三个都必须在")
                .containsExactly("confirm-resolution", "report-unresolved", "cancel");
    }

    /** 待确认状态下 IT 侧只暴露已实现的动作，负责人自己不是提交人，不应拿到确认按钮。 */
    @Test
    void detailHidesConfirmResolutionForAssignee() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_REQUESTER_ACTION");
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 4L));

        assertActions(service.detail(TICKET_NO));
    }

    @Test
    void detailHidesConfirmResolutionWithoutRequesterActionAuthority() {
        stubCurrentUser(REQUESTER_ID);
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 4L));

        assertActions(service.detail(TICKET_NO));
    }

    /** 同状态但当前用户是负责人而不是提交人：即使同时持有提交人权限，两格都不出现。 */
    @Test
    void detailHidesConfirmAndReportUnresolvedForAssigneeOfWaitingForConfirmation() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_REQUESTER_ACTION", "TICKET_PROCESS");
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 4L));

        assertActions(service.detail(TICKET_NO));
    }

    // ---------- allowedActions：片 A 新增两格 ----------

    /** 「待补充」+ 本人是负责人 + {@code TICKET_PROCESS}：撤回这一格，外加大片 C 的两个调整动作。 */
    @Test
    void detailAllowsWithdrawSupplementRequestForCurrentAssigneeOfWaitingForRequester() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_PROCESS");
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));

        TicketDetailResult result = service.detail(TICKET_NO);

        assertThat(result.allowedActions())
                .as("待补充时负责人能撤回、也能调整分类与优先级，但没有转交权限")
                .containsExactly("withdraw-supplement-request",
                        "change-category", "change-priority");
    }

    @Test
    void detailHidesWithdrawSupplementRequestWithoutProcessAuthority() {
        stubCurrentUser(IT_USER_ID);
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));

        assertActions(service.detail(TICKET_NO));
    }

    @Test
    void detailHidesWithdrawSupplementRequestForNonAssignee() {
        stubCurrentUser(OTHER_IT_USER_ID);
        grant("TICKET_PROCESS");
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));

        assertActions(service.detail(TICKET_NO));
    }

    @Test
    void detailHidesWithdrawSupplementRequestWhenTicketHasNoAssignee() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_PROCESS");
        stubVisible(detailRow(WAITING_FOR_REQUESTER, null, 5L));

        assertActions(service.detail(TICKET_NO));
    }

    // ---------- allowedActions：片 B 新增两格 ----------

    /**
     * 「待补充」+ 本人是提交人 + {@code TICKET_REQUESTER_ACTION}：补充与撤销两格。
     *
     * <p>提交人在这个状态上不是负责人，因此不能复用 {@code canProcess} 一族的判定；
     * 同时也不该拿到 {@code report-unresolved} 或 {@code confirm-resolution}——
     * 那两格只属于「待确认」。{@code cancel} 是片 D 新增的：撤销在四种非终态上都可用。</p>
     */
    @Test
    void detailAllowsSupplementForRequesterOfWaitingForRequester() {
        stubCurrentUser(REQUESTER_ID);
        grant("TICKET_REQUESTER_ACTION");
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));

        TicketDetailResult result = service.detail(TICKET_NO);

        assertThat(result.allowedActions())
                .as("提交人在待补充上能补充，也能直接撤销")
                .containsExactly("supplement", "cancel");
    }

    @Test
    void detailHidesSupplementWithoutRequesterActionAuthority() {
        stubCurrentUser(REQUESTER_ID);
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));

        assertActions(service.detail(TICKET_NO));
    }

    /** 同状态但当前用户是负责人：即使同时持有提交人权限，补充那一格也不出现。 */
    @Test
    void detailHidesSupplementForAssigneeOfWaitingForRequester() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_REQUESTER_ACTION", "TICKET_PROCESS");
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));

        TicketDetailResult result = service.detail(TICKET_NO);

        assertThat(result.allowedActions())
                .as("负责人能撤回也能调整，但不能替提交人补充信息")
                .containsExactly("withdraw-supplement-request",
                        "change-category", "change-priority");
    }

    /**
     * 等待态专属的格子只属于各自的等待态：{@code PROCESSING} 上即使持有全部相关权限，
     * 也不该多出 {@code withdraw-supplement-request}、{@code supplement} 或
     * {@code report-unresolved}——它们只有工单停在对应等待态时才可达。
     */
    @Test
    void detailDoesNotOfferReturnActionsOutsideTheirWaitingStates() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_VIEW_QUEUE", "TICKET_CLAIM", "TICKET_PROCESS",
                "TICKET_REQUESTER_ACTION");
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        TicketDetailResult result = service.detail(TICKET_NO);

        assertThat(result.allowedActions())
                .as("处理中只暴露处理、提交解决结果、请求补充与两个调整动作")
                .containsExactly(
                        "add-processing-record", "submit-resolution", "request-supplement",
                        "change-category", "change-priority");
    }

    /** 终态不再暴露任何动作：本人是提交人且持有全部权限、且具备领取资格也不能例外。 */
    @Test
    void detailReturnsNoActionsForTerminalTicket() {
        stubCurrentUser(REQUESTER_ID);
        grant("TICKET_VIEW_OWN", "TICKET_VIEW_QUEUE", "TICKET_VIEW_PARTICIPATED",
                "TICKET_CLAIM", "TICKET_PROCESS", "TICKET_REQUESTER_ACTION",
                "TICKET_TRANSFER");
        when(ticketClaimantPort.isEligibleClaimant(REQUESTER_ID)).thenReturn(true);
        stubVisible(detailRow(COMPLETED, IT_USER_ID, 5L));

        TicketDetailResult result = service.detail(TICKET_NO);

        assertThat(result.allowedActions())
                .as("COMPLETED 是终态，不再有任何可变动作")
                .isEmpty();
    }

    @Test
    void detailMapsRemainingFieldsAndConvertsTimestampsToUtc() {
        TicketDetailRow row = detailRow(COMPLETED, IT_USER_ID, 5L);
        row.setCompletionMethod("REQUESTER_CONFIRMED");
        row.setCloseMethod("MANUAL_CLOSE");
        row.setCloseReason("重复工单");
        row.setEndedAt(ENDED_AT);
        stubCurrentUser(REQUESTER_ID);
        stubVisible(row);

        TicketDetailResult result = service.detail(TICKET_NO);

        assertThat(result.ticketNo()).isEqualTo(TICKET_NO);
        assertThat(result.title()).isEqualTo("打印机无法连接");
        assertThat(result.description()).as("正文按可见性 SQL 授权后原样透传").isEqualTo("三楼打印机离线");
        assertThat(result.category())
                .isEqualTo(new TicketCategorySummaryResult(CATEGORY_ID, "办公设备"));
        assertThat(result.priority()).isEqualTo(HIGH);
        assertThat(result.status()).isEqualTo(COMPLETED);
        assertThat(result.requester())
                .isEqualTo(new TicketUserSummaryResult(REQUESTER_ID, REQUESTER_DISPLAY_NAME));
        assertThat(result.assignee())
                .isEqualTo(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        assertThat(result.version()).isEqualTo(5L);
        assertThat(result.completionMethod()).isEqualTo("REQUESTER_CONFIRMED");
        assertThat(result.closeMethod()).isEqualTo("MANUAL_CLOSE");
        assertThat(result.closeReason()).isEqualTo("重复工单");
        assertThat(result.actionDeadlineAt()).isEqualTo(DEADLINE_AT.atOffset(ZoneOffset.UTC));
        assertThat(result.endedAt()).isEqualTo(ENDED_AT.atOffset(ZoneOffset.UTC));
        assertThat(result.createdAt()).isEqualTo(CREATED_AT.atOffset(ZoneOffset.UTC));
        assertThat(result.updatedAt()).isEqualTo(UPDATED_AT.atOffset(ZoneOffset.UTC));
        assertThat(result.allowedActions()).isEmpty();
    }

    @Test
    void detailReturnsNullAssigneeSummaryWhenTicketHasNoAssignee() {
        stubCurrentUser(IT_USER_ID);
        stubVisible(detailRow(PENDING, null, 0L));

        TicketDetailResult result = service.detail(TICKET_NO);

        assertThat(result.assignee())
                .as("无人负责时摘要为 null，而不是 id/名称为空的空对象")
                .isNull();
    }

    // ---------- records ----------

    /** 时间线与详情共用同一套可见性判定：不可见时连记录条数都不能泄漏。 */
    @Test
    void recordsReportsNotFoundAndSkipsTimelineWhenTicketIsNotVisible() {
        stubCurrentUser(IT_USER_ID);

        assertApiException(() -> service.records(TICKET_NO, recordQuery()),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void recordsReadsTimelineOfVisibleTicketWithQueryPaging() {
        stubCurrentUser(REQUESTER_ID);
        stubVisible(detailRow(PENDING, null, 0L));
        stubTimeline(List.of(), 0);
        TicketRecordQuery query = new TicketRecordQuery();
        query.setPageNo(2);
        query.setPageSize(5);

        service.records(TICKET_NO, query);

        ArgumentCaptor<Page<TicketRecordRow>> pageCaptor = recordPageCaptor();
        verify(ticketRecordMapper).selectTimelinePage(pageCaptor.capture(), eq(TICKET_ID));
        assertThat(pageCaptor.getValue().getCurrent()).as("页码来自 query.current").isEqualTo(2L);
        assertThat(pageCaptor.getValue().getSize()).as("每页条数来自 query.size").isEqualTo(5L);
    }

    @Test
    void recordsMapsSequenceTypeActorAndUtcTimestamp() {
        stubCurrentUser(REQUESTER_ID);
        stubVisible(detailRow(PENDING, null, 0L));
        stubTimeline(List.of(recordRow("PROCESS")), 1);

        PageResult<TicketRecordResult> result = service.records(TICKET_NO, recordQuery());

        assertThat(result.totalElements()).isEqualTo(1);
        TicketRecordResult record = result.items().get(0);
        assertThat(record.sequenceNo()).isEqualTo(2);
        assertThat(record.recordType()).isEqualTo("PROCESS");
        assertThat(record.actorType()).isEqualTo("USER");
        assertThat(record.actor())
                .isEqualTo(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        assertThat(record.createdAt()).isEqualTo(CREATED_AT.atOffset(ZoneOffset.UTC));
        assertThat(record.context()).containsExactlyInAnyOrderEntriesOf(Map.of("content", "已更换网线"));
    }

    /** 系统动作没有 actor_user_id，不能渲染成 id/名称为空的空对象。 */
    @Test
    void recordsReturnsNullActorWhenActorUserIdIsMissing() {
        TicketRecordRow row = recordRow("COMPLETION");
        row.setActorUserId(null);
        row.setActorDisplayName(null);
        stubCurrentUser(REQUESTER_ID);
        stubVisible(detailRow(PENDING, null, 0L));
        stubTimeline(List.of(row), 1);

        PageResult<TicketRecordResult> result = service.records(TICKET_NO, recordQuery());

        assertThat(result.items().get(0).actor()).isNull();
    }

    @Test
    void recordsRejectsUnsupportedRecordType() {
        stubCurrentUser(REQUESTER_ID);
        stubVisible(detailRow(PENDING, null, 0L));
        stubTimeline(List.of(recordRow("ALIEN")), 1);

        assertThatThrownBy(() -> service.records(TICKET_NO, recordQuery()))
                .as("未知记录类型必须显式失败，不能静默返回空 context")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不支持的工单记录类型：ALIEN");
    }

    // ---------- 时间线 context 白名单 ----------

    /** CREATE：只暴露新状态与新分类/优先级。 */
    @Test
    void createRecordContextExposesStatusCategoryAndPriority() {
        Map<String, Object> context = contextOf("CREATE");

        assertThat(context)
                .as("CREATE 的 context 只允许 toStatus/categoryId/priority")
                .containsOnlyKeys("toStatus", "categoryId", "priority")
                .containsEntry("toStatus", PROCESSING)
                .containsEntry("categoryId", CATEGORY_ID)
                .containsEntry("priority", HIGH);
    }

    @Test
    void createRecordContextOmitsNullCategoryIdWhenTicketWasCreatedWithoutCategory() {
        TicketRecordRow row = recordRow("CREATE");
        row.setToCategoryId(null);

        Map<String, Object> context = contextOf(row);

        assertThat(context)
                .as("空字段不进入 context：客户端按键是否存在判断，而不是按 null 判断")
                .containsOnlyKeys("toStatus", "priority")
                .doesNotContainKey("categoryId");
    }

    @Test
    void claimRecordContextExposesAssigneeAndStatusTransition() {
        Map<String, Object> context = contextOf("CLAIM");

        assertThat(context)
                .as("CLAIM 的 context 只允许 assigneeId/fromStatus/toStatus")
                .containsOnlyKeys("assigneeId", "fromStatus", "toStatus")
                .containsEntry("assigneeId", IT_USER_ID)
                .containsEntry("fromStatus", PENDING)
                .containsEntry("toStatus", PROCESSING);
    }

    @Test
    void contentRecordTypesExposeOnlyContent() {
        for (String recordType : List.of("PROCESS", "REQUESTER_SUPPLEMENT")) {
            assertThat(contextOf(recordType))
                    .as("%s 的 context 只允许 content", recordType)
                    .containsOnlyKeys("content")
                    .containsEntry("content", "已更换网线");
        }
    }

    @Test
    void categoryChangeRecordContextExposesOnlyCategoryFieldsAndReason() {
        Map<String, Object> context = contextOf("CATEGORY_CHANGE");

        assertThat(context)
                .as("CATEGORY_CHANGE 的 context 只允许 from/toCategoryId 与 reason")
                .containsOnlyKeys("fromCategoryId", "toCategoryId", "reason")
                .containsEntry("fromCategoryId", 2L)
                .containsEntry("toCategoryId", CATEGORY_ID)
                .containsEntry("reason", "原因说明");
    }

    @Test
    void priorityChangeRecordContextExposesOnlyPriorityFieldsAndReason() {
        Map<String, Object> context = contextOf("PRIORITY_CHANGE");

        assertThat(context)
                .as("PRIORITY_CHANGE 的 context 只允许 from/toPriority 与 reason")
                .containsOnlyKeys("fromPriority", "toPriority", "reason")
                .containsEntry("fromPriority", "LOW")
                .containsEntry("toPriority", HIGH)
                .containsEntry("reason", "原因说明");
    }

    @Test
    void transferRecordTypesExposeOnlyAssigneeFieldsAndReason() {
        for (String recordType : List.of("TRANSFER", "ADMIN_HANDOFF")) {
            assertThat(contextOf(recordType))
                    .as("%s 的 context 只允许 from/toAssigneeId 与 reason", recordType)
                    .containsOnlyKeys("fromAssigneeId", "toAssigneeId", "reason")
                    .containsEntry("fromAssigneeId", OTHER_IT_USER_ID)
                    .containsEntry("toAssigneeId", IT_USER_ID)
                    .containsEntry("reason", "原因说明");
        }
    }

    @Test
    void supplementRequestAndResolutionContextsExposeContentDeadlineAndStatus() {
        for (String recordType : List.of("SUPPLEMENT_REQUEST", "RESOLUTION")) {
            assertThat(contextOf(recordType))
                    .as("%s 的 context 只允许 content/deadlineAt/fromStatus/toStatus", recordType)
                    .containsOnlyKeys("content", "deadlineAt", "fromStatus", "toStatus")
                    .containsEntry("content", "已更换网线")
                    .containsEntry("deadlineAt", DEADLINE_AT.atOffset(ZoneOffset.UTC))
                    .containsEntry("fromStatus", PENDING)
                    .containsEntry("toStatus", PROCESSING);
        }
    }

    @Test
    void reasonOnlyRecordTypesExposeReasonAndStatus() {
        for (String recordType : List.of(
                "SUPPLEMENT_REQUEST_WITHDRAWN", "UNSATISFIED_FEEDBACK", "CANCELLATION")) {
            assertThat(contextOf(recordType))
                    .as("%s 的 context 只允许 reason/fromStatus/toStatus", recordType)
                    .containsOnlyKeys("reason", "fromStatus", "toStatus")
                    .containsEntry("reason", "原因说明")
                    .containsEntry("fromStatus", PENDING)
                    .containsEntry("toStatus", PROCESSING);
        }
    }

    @Test
    void completionRecordContextExposesCompletionMethodAndStatus() {
        Map<String, Object> context = contextOf("COMPLETION");

        assertThat(context)
                .as("COMPLETION 的 context 只允许 completionMethod/fromStatus/toStatus")
                .containsOnlyKeys("completionMethod", "fromStatus", "toStatus")
                .containsEntry("completionMethod", "REQUESTER_CONFIRMED")
                .containsEntry("fromStatus", PENDING)
                .containsEntry("toStatus", PROCESSING);
    }

    @Test
    void closureRecordContextExposesCloseFieldsReasonAndStatus() {
        Map<String, Object> context = contextOf("CLOSURE");

        assertThat(context)
                .as("CLOSURE 的 context 只允许 closeMethod/closeReason/reason 与状态迁移")
                .containsOnlyKeys("closeMethod", "closeReason", "reason",
                        "fromStatus", "toStatus")
                .containsEntry("closeMethod", "ADMIN_CLOSE")
                .containsEntry("closeReason", "重复提交")
                .containsEntry("reason", "原因说明")
                .containsEntry("fromStatus", PENDING)
                .containsEntry("toStatus", PROCESSING);
    }

    // ---------- allowedActions：片 C 新增三格 ----------

    /**
     * 「处理中」+ 本人是负责人 + {@code TICKET_PROCESS} 与 {@code TICKET_TRANSFER}：六个动作齐。
     *
     * <p>三个动作共用同一条"状态属于可调整态且本人是负责人"的身份判定，权限各自独立：
     * 调整需要 {@code TICKET_PROCESS}，转交需要 {@code TICKET_TRANSFER}。缺任何一条权限，
     * 对应的按钮就不该出现——界面按 {@code allowedActions} 渲染，</p>
     */
    @Test
    void detailAllowsAdjustAndTransferForAssigneeWithBothAuthorities() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_PROCESS", "TICKET_TRANSFER");
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertActions(service.detail(TICKET_NO),
                "add-processing-record", "submit-resolution", "request-supplement",
                "change-category", "change-priority", "transfer");
    }

    /**
     * 转交与"处理"是两条独立授权：只有 {@code TICKET_TRANSFER} 时，转交仍然可用，
     * 而依赖 {@code TICKET_PROCESS} 的处理记录、解决结果、请求补充与两个调整动作都不出现。
     *
     * <p>返回 {@code 409} 的角色判定只要求"本人是当前负责人"；能否转交由权限码单独决定，
     * 因此可以只给一个角色配 {@code TICKET_TRANSFER} 而不给处理权限。</p>
     */
    @Test
    void detailAllowsTransferWithoutProcessAuthority() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_TRANSFER");
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertActions(service.detail(TICKET_NO), "transfer");
    }

    /** 有转交权限但不是当前负责人：不能凭权限替别人调整或转交。 */
    @Test
    void detailHidesAdjustAndTransferForNonAssignee() {
        stubCurrentUser(OTHER_IT_USER_ID);
        grant("TICKET_PROCESS", "TICKET_TRANSFER");
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertActions(service.detail(TICKET_NO));
    }

    /** 无人负责的工单不存在"当前负责人"，三格都不出现。 */
    @Test
    void detailHidesAdjustAndTransferWhenTicketHasNoAssignee() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_PROCESS", "TICKET_TRANSFER");
        stubVisible(detailRow(PROCESSING, null, 3L));

        assertActions(service.detail(TICKET_NO));
    }

    /**
     * 提交人自己不是负责人：即使持有全部相关权限也不该看到调整与转交。
     *
     * <p>片 D 之后这张单上他仍有一件事可做——撤销（四种非终态 + 本人是提交人 +
     * {@code TICKET_REQUESTER_ACTION}），因此期望值从空集变成一格。负责人那一族的五个动作
     * 一个都不能出现：权限齐备不等于可以替别人处理。</p>
     */
    @Test
    void detailHidesAdjustAndTransferForRequesterOfProcessing() {
        stubCurrentUser(REQUESTER_ID);
        grant("TICKET_PROCESS", "TICKET_TRANSFER", "TICKET_REQUESTER_ACTION");
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertActions(service.detail(TICKET_NO), "cancel");
    }

    /** 「待确认」不在可调整态：负责人持有两个权限也拿不到这三格。 */
    @Test
    void detailHidesAdjustAndTransferOnWaitingForConfirmation() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_PROCESS", "TICKET_TRANSFER");
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 3L));

        assertActions(service.detail(TICKET_NO));
    }

    /** 「待补充」+ 负责人 + 两个权限：撤回在前、调整居中、转交最后，顺序固定。 */
    @Test
    void detailAllowsAdjustAndTransferOnWaitingForRequesterInFixedOrder() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_PROCESS", "TICKET_TRANSFER");
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));

        assertActions(service.detail(TICKET_NO),
                "withdraw-supplement-request",
                "change-category", "change-priority", "transfer");
    }

    // ---------- allowedActions：片 D 新增两格 ----------

    /**
     * 「处理中」+ 本人是负责人 + 两条授权齐备：关闭排在转交之后。
     *
     * <p>{@code canClose} 复用 {@code canProcess} 的同一条身份判定，再叠 {@code TICKET_CLOSE}。
     * 关闭与撤销在这张表上永远不会同时出现（前者要求"本人是负责人"，后者要求"本人是提交人"，
     * 而 {@code ck_ticket_assignee_not_requester} 禁止同一人是两者），所以顺序只需各自钉住。</p>
     */
    @Test
    void detailAllowsCloseForAssigneeWithBothAuthorities() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_PROCESS", "TICKET_CLOSE");
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertActions(service.detail(TICKET_NO),
                "add-processing-record", "submit-resolution", "request-supplement",
                "change-category", "change-priority", "close");
    }

    /** 缺 {@code TICKET_CLOSE}：处理权限一族照旧，但关闭那一格不出现。 */
    @Test
    void detailHidesCloseWithoutCloseAuthority() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_PROCESS");
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertActions(service.detail(TICKET_NO),
                "add-processing-record", "submit-resolution", "request-supplement",
                "change-category", "change-priority");
    }

    /**
     * 反向的一格：只有 {@code TICKET_CLOSE} 而没有 {@code TICKET_PROCESS} 同样关不了。
     *
     * <p>这是 2026-10-08 的用户裁决——关闭结束整张工单，必须建立在处理权限之上；
     * 与片 C 的 {@code transfer} 只要求 {@code TICKET_TRANSFER} 是两条不同的口径。</p>
     */
    @Test
    void detailHidesCloseWithoutProcessAuthority() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_CLOSE");
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertActions(service.detail(TICKET_NO));
    }

    /** 关闭只允许「处理中」：待补充时负责人能撤回与调整，但关不掉这张单。 */
    @Test
    void detailHidesCloseOutsideProcessing() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_PROCESS", "TICKET_CLOSE");
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));

        assertActions(service.detail(TICKET_NO),
                "withdraw-supplement-request", "change-category", "change-priority");
    }

    @Test
    void detailHidesCloseForNonAssignee() {
        stubCurrentUser(OTHER_IT_USER_ID);
        grant("TICKET_PROCESS", "TICKET_CLOSE");
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertActions(service.detail(TICKET_NO));
    }

    /**
     * 撤销是四种非终态上提交人的动作，等待态各自还有自己的那一格。
     *
     * <p>期望值按状态逐个给：{@code PENDING} 与 {@code PROCESSING} 只有撤销，
     * {@code WAITING_FOR_REQUESTER} 多一格补充，{@code WAITING_FOR_CONFIRMATION} 多两格
     * （确认与反馈未解决）。撤销一律排在最后。</p>
     */
    @ParameterizedTest(name = "requester sees cancel on {0}")
    @MethodSource("cancelableStatusesWithExpectedActions")
    void detailAllowsCancelForRequesterOnEveryNonTerminalStatus(
            String status, Long assigneeId, List<String> expectedActions) {
        stubCurrentUser(REQUESTER_ID);
        grant("TICKET_REQUESTER_ACTION");
        stubVisible(detailRow(status, assigneeId, 3L));

        assertThat(service.detail(TICKET_NO).allowedActions())
                .as("撤销覆盖四种非终态，且不挤掉各等待态自己的那一格")
                .containsExactlyElementsOf(expectedActions);
    }

    static Stream<Arguments> cancelableStatusesWithExpectedActions() {
        return Stream.of(
                Arguments.of(PENDING, null, List.of("cancel")),
                Arguments.of(PROCESSING, IT_USER_ID, List.of("cancel")),
                Arguments.of(WAITING_FOR_REQUESTER, IT_USER_ID, List.of("supplement", "cancel")),
                Arguments.of(WAITING_FOR_CONFIRMATION, IT_USER_ID,
                        List.of("confirm-resolution", "report-unresolved", "cancel")));
    }

    @Test
    void detailHidesCancelWithoutRequesterActionAuthority() {
        stubCurrentUser(REQUESTER_ID);
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertActions(service.detail(TICKET_NO));
    }

    /**
     * 「处理中」的当前负责人拿不到撤销：他不是提交人。
     *
     * <p>这里刻意让负责人也持有 {@code TICKET_REQUESTER_ACTION}——权限齐备但身份不对，
     * 撤销那一格仍然不能出现，与动作接口的 409 判定同源。</p>
     */
    @Test
    void detailHidesCancelForAssigneeOfProcessing() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_REQUESTER_ACTION", "TICKET_PROCESS");
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertActions(service.detail(TICKET_NO),
                "add-processing-record", "submit-resolution", "request-supplement",
                "change-category", "change-priority");
    }

    /** 三个终态都不再有任何出口，撤销也不例外。 */
    @ParameterizedTest(name = "terminal {0} has no actions")
    @ValueSource(strings = {COMPLETED, CANCELED, CLOSED})
    void detailReturnsNoActionsForEveryTerminalStatus(String status) {
        stubCurrentUser(REQUESTER_ID);
        grant("TICKET_REQUESTER_ACTION");
        stubVisible(detailRow(status, IT_USER_ID, 5L));

        assertThat(service.detail(TICKET_NO).allowedActions())
                .as("%s 是终态，提交人也不再能撤销", status)
                .isEmpty();
    }

    // ---------- transferCandidates ----------

    /** 候选人接口与转交动作共用 {@code TICKET_TRANSFER}，且权限闸门先于可见性。 */
    @Test
    void transferCandidatesRejectsActorWithoutTransferAuthority() {
        stubCurrentUser(IT_USER_ID);

        assertApiException(() -> service.transferCandidates(TICKET_NO),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verify(ticketMapper, never())
                .selectVisibleDetail(any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(ticketMapper, never()).selectTransferCandidates(anyLong(), anyLong());
    }

    @Test
    void transferCandidatesReportsNotFoundWhenTicketIsNotVisible() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_TRANSFER");

        assertApiException(() -> service.transferCandidates(TICKET_NO),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verify(ticketMapper, never()).selectTransferCandidates(anyLong(), anyLong());
    }

    /** 契约要求该接口重新校验状态：不是可调整态就不返回候选人。 */
    @Test
    void transferCandidatesReportsConflictWhenTicketIsNotAdjustable() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_TRANSFER");
        stubVisible(detailRow(PENDING, null, 3L));

        ApiException exception = assertApiException(() -> service.transferCandidates(TICKET_NO),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(3L);
        assertThat(exception.resourceStatus()).isEqualTo(PENDING);
        verify(ticketMapper, never()).selectTransferCandidates(anyLong(), anyLong());
    }

    /** 可查看但不是负责人（例如历史参与者）与动作接口口径一致：409，不是 403。 */
    @Test
    void transferCandidatesReportsConflictWhenActorIsNotTheCurrentAssignee() {
        stubCurrentUser(OTHER_IT_USER_ID);
        grant("TICKET_TRANSFER");
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertApiException(() -> service.transferCandidates(TICKET_NO),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never()).selectTransferCandidates(anyLong(), anyLong());
    }

    @Test
    void transferCandidatesReportsConflictWhenTicketHasNoAssignee() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_TRANSFER");
        stubVisible(detailRow(PROCESSING, null, 3L));

        assertApiException(() -> service.transferCandidates(TICKET_NO),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never()).selectTransferCandidates(anyLong(), anyLong());
    }

    /** 成功路径：排除条件由 SQL 用「提交人 + 当前负责人」两个入参表达，映射只带两个字段。 */
    @Test
    void transferCandidatesMapsRowsAndPassesRequesterAndCurrentAssignee() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_TRANSFER");
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));
        when(ticketMapper.selectTransferCandidates(REQUESTER_ID, IT_USER_ID))
                .thenReturn(List.of(assigneeRow(NEW_IT_USER_ID, NEW_IT_DISPLAY_NAME)));

        List<TicketAssigneeOptionResult> candidates = service.transferCandidates(TICKET_NO);

        verify(ticketMapper).selectTransferCandidates(REQUESTER_ID, IT_USER_ID);
        assertThat(candidates)
                .as("只暴露选择新负责人所需的最小字段")
                .containsExactly(
                        new TicketAssigneeOptionResult(NEW_IT_USER_ID, NEW_IT_DISPLAY_NAME));
    }

    /** 没有可转交对象时返回空列表，而不是 null——前端据此渲染空态。 */
    @Test
    void transferCandidatesReturnsEmptyListWhenNoOtherItUserExists() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_TRANSFER");
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 3L));
        when(ticketMapper.selectTransferCandidates(REQUESTER_ID, IT_USER_ID))
                .thenReturn(List.of());

        assertThat(service.transferCandidates(TICKET_NO)).isEmpty();
    }

    // ---------- 事务边界 ----------

    /** 四个查询方法都只读：不写库，也不该在事务里拿写锁。 */
    @Test
    void queryMethodsDeclareReadOnlyTransactions() throws NoSuchMethodException {
        assertReadOnly("page", TicketQuery.class);
        assertReadOnly("detail", String.class);
        assertReadOnly("records", String.class, TicketRecordQuery.class);
        assertReadOnly("transferCandidates", String.class);
    }

    // ---------- 辅助 ----------

    private static void assertReadOnly(String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Transactional transactional = TicketQueryServiceImpl.class
                .getMethod(name, parameterTypes)
                .getAnnotation(Transactional.class);
        assertThat(transactional).as("%s 必须声明事务", name).isNotNull();
        assertThat(transactional.readOnly()).as("%s 只读", name).isTrue();
    }

    private void assertActions(TicketDetailResult result, String... expected) {
        assertThat(result.allowedActions())
                .as("allowedActions 是前端按钮的唯一来源")
                .containsExactly(expected);
    }

    private static TicketQuery requestedByMeQuery() {
        TicketQuery query = new TicketQuery();
        query.setScope(TicketScope.REQUESTED_BY_ME);
        return query;
    }

    private static TicketRecordQuery recordQuery() {
        return new TicketRecordQuery();
    }

    private void stubCurrentUser(long userId) {
        when(currentRequesterPort.currentUserId()).thenReturn(userId);
    }

    /** 按编码逐个授权；未列出的编码保持 Mockito 默认的 false。 */
    private void grant(String... authorities) {
        for (String authority : authorities) {
            when(ticketReadPermissionPort.hasAuthority(authority)).thenReturn(true);
        }
    }

    private void stubVisible(TicketDetailRow row) {
        when(ticketMapper.selectVisibleDetail(
                any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(row);
    }

    /** 列表中台替身：把传入的 Page 当作 MyBatis-Plus 分页插件回填后的结果返回。 */
    private void stubScopedPage(List<TicketListRow> rows, long total) {
        doAnswer(invocation -> {
            Page<TicketListRow> page = invocation.getArgument(0);
            page.setRecords(rows);
            page.setTotal(total);
            return page;
        }).when(ticketMapper).selectScopedPage(any(), anyLong(), any(), any(), any(), any());
    }

    private void stubTimeline(List<TicketRecordRow> rows, long total) {
        doAnswer(invocation -> {
            Page<TicketRecordRow> page = invocation.getArgument(0);
            page.setRecords(rows);
            page.setTotal(total);
            return page;
        }).when(ticketRecordMapper).selectTimelinePage(any(), anyLong());
    }

    /** 走一遍可见工单的时间线查询，取回单条记录的 context。 */
    private Map<String, Object> contextOf(String recordType) {
        return contextOf(recordRow(recordType));
    }

    private Map<String, Object> contextOf(TicketRecordRow row) {
        stubCurrentUser(REQUESTER_ID);
        stubVisible(detailRow(PENDING, null, 0L));
        stubTimeline(List.of(row), 1);

        return service.records(TICKET_NO, recordQuery()).items().get(0).context();
    }

    private static TicketListRow listRow() {
        TicketListRow row = new TicketListRow();
        row.setTicketNo(TICKET_NO);
        row.setTitle("打印机无法连接");
        row.setCategoryId(CATEGORY_ID);
        row.setCategoryName("办公设备");
        row.setPriority(HIGH);
        row.setStatus(PENDING);
        row.setRequesterId(REQUESTER_ID);
        row.setRequesterDisplayName(REQUESTER_DISPLAY_NAME);
        row.setAssigneeId(IT_USER_ID);
        row.setAssigneeDisplayName(IT_DISPLAY_NAME);
        row.setActionDeadlineAt(DEADLINE_AT);
        row.setCreatedAt(CREATED_AT);
        row.setUpdatedAt(UPDATED_AT);
        row.setVersion(3L);
        return row;
    }

    private static TicketDetailRow detailRow(String status, Long assigneeId, long version) {
        return detailRow(status, assigneeId, version, REQUESTER_ID);
    }

    private static TicketAssigneeRow assigneeRow(long id, String displayName) {
        TicketAssigneeRow row = new TicketAssigneeRow();
        row.setId(id);
        row.setDisplayName(displayName);
        return row;
    }

    private static TicketDetailRow detailRow(
            String status, Long assigneeId, long version, long requesterId) {
        TicketDetailRow row = new TicketDetailRow();
        row.setId(TICKET_ID);
        row.setTicketNo(TICKET_NO);
        row.setTitle("打印机无法连接");
        row.setDescription("三楼打印机离线");
        row.setCategoryId(CATEGORY_ID);
        row.setCategoryName("办公设备");
        row.setPriority(HIGH);
        row.setStatus(status);
        row.setRequesterId(requesterId);
        row.setRequesterDisplayName(REQUESTER_DISPLAY_NAME);
        row.setAssigneeId(assigneeId);
        row.setAssigneeDisplayName(assigneeId == null ? null : IT_DISPLAY_NAME);
        row.setActionDeadlineAt(DEADLINE_AT);
        row.setVersion(version);
        row.setCreatedAt(CREATED_AT);
        row.setUpdatedAt(UPDATED_AT);
        return row;
    }

    /** 每个可选字段都给非空值：这样"context 里多出别的键"一定会被白名单断言抓住。 */
    private static TicketRecordRow recordRow(String recordType) {
        TicketRecordRow row = new TicketRecordRow();
        row.setId(11L);
        row.setTicketId(TICKET_ID);
        row.setSequenceNo(2);
        row.setRecordType(recordType);
        row.setActorType("USER");
        row.setActorUserId(IT_USER_ID);
        row.setActorDisplayName(IT_DISPLAY_NAME);
        row.setContent("已更换网线");
        row.setReason("原因说明");
        row.setFromStatus(PENDING);
        row.setToStatus(PROCESSING);
        row.setFromAssigneeId(OTHER_IT_USER_ID);
        row.setToAssigneeId(IT_USER_ID);
        row.setFromCategoryId(2L);
        row.setToCategoryId(CATEGORY_ID);
        row.setFromPriority("LOW");
        row.setToPriority(HIGH);
        row.setDeadlineAt(DEADLINE_AT);
        row.setCompletionMethod("REQUESTER_CONFIRMED");
        row.setCloseMethod("ADMIN_CLOSE");
        row.setCloseReason("重复提交");
        row.setCreatedAt(CREATED_AT);
        return row;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<Page<TicketListRow>> pageCaptor() {
        return ArgumentCaptor.forClass((Class) Page.class);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<Page<TicketRecordRow>> recordPageCaptor() {
        return ArgumentCaptor.forClass((Class) Page.class);
    }

    private static ApiException assertApiException(
            ThrowingCallable action, HttpStatus status, String code) {
        Throwable thrown = catchThrowable(action);
        assertThat(thrown).as("应抛出 ApiException，实际为 %s", thrown)
                .isInstanceOf(ApiException.class);
        ApiException exception = (ApiException) thrown;
        assertThat(exception.status()).as("HTTP 状态").isEqualTo(status);
        assertThat(exception.code()).as("稳定错误编码").isEqualTo(code);
        return exception;
    }
}
