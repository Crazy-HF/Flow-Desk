package com.flowdesk.ticket.application.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.ticket.application.port.CurrentRequesterPort;
import com.flowdesk.ticket.application.port.TicketClaimantPort;
import com.flowdesk.ticket.application.port.TicketReadPermissionPort;
import com.flowdesk.ticket.application.query.TicketQuery;
import com.flowdesk.ticket.application.query.TicketRecordQuery;
import com.flowdesk.ticket.application.result.TicketCategorySummaryResult;
import com.flowdesk.ticket.application.result.TicketDetailResult;
import com.flowdesk.ticket.application.result.TicketListItemResult;
import com.flowdesk.ticket.application.result.TicketRecordResult;
import com.flowdesk.ticket.application.result.TicketUserSummaryResult;
import com.flowdesk.ticket.domain.TicketScope;
import com.flowdesk.ticket.infrastructure.persistence.TicketDetailRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketListRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketRecordRow;
import com.flowdesk.ticket.mapper.TicketMapper;
import com.flowdesk.ticket.mapper.TicketRecordMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
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
 */
@ExtendWith(MockitoExtension.class)
// 多个用例共享"当前用户 + 权限 + 可见性行"替身，未使用的桩不应判定为失败。
@MockitoSettings(strictness = Strictness.LENIENT)
class TicketQueryServiceImplTest {

    private static final long REQUESTER_ID = 42L;
    private static final long IT_USER_ID = 7L;
    private static final long OTHER_IT_USER_ID = 9L;
    private static final long TICKET_ID = 1001L;
    private static final long CATEGORY_ID = 3L;

    private static final String TICKET_NO = "FD-20261006-001";
    private static final String REQUESTER_DISPLAY_NAME = "演示员工";
    private static final String IT_DISPLAY_NAME = "演示 IT 支持人员";
    private static final String PENDING = "PENDING";
    private static final String PROCESSING = "PROCESSING";
    private static final String WAITING_FOR_REQUESTER = "WAITING_FOR_REQUESTER";
    private static final String WAITING_FOR_CONFIRMATION = "WAITING_FOR_CONFIRMATION";
    private static final String COMPLETED = "COMPLETED";
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
     * 2026-10-06 缺陷回归：{@code PROCESSING} 的当前负责人必须同时拿到追加处理记录与提交解决结果。
     *
     * <p>两条动作顺序固定；{@code submit-resolution} 曾经缺失，导致负责人能写处理记录却交不出
     * 解决结果——界面按 {@code allowedActions} 渲染按钮，缺一项就等于该动作不可达。</p>
     */
    @Test
    void detailAllowsProcessingAndResolutionActionsForAssigneeInFixedOrder() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_PROCESS");
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        TicketDetailResult result = service.detail(TICKET_NO);

        assertThat(result.allowedActions())
                .as("负责人拿到的两个动作，顺序固定且 submit-resolution 必须在")
                .containsExactly("add-processing-record", "submit-resolution");
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
     */
    @Test
    void detailAllowsConfirmAndReportUnresolvedForRequesterInFixedOrder() {
        stubCurrentUser(REQUESTER_ID);
        grant("TICKET_REQUESTER_ACTION");
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 4L));

        TicketDetailResult result = service.detail(TICKET_NO);

        assertThat(result.allowedActions())
                .as("提交人在待确认上有两个互斥选择，顺序固定且两个都必须在")
                .containsExactly("confirm-resolution", "report-unresolved");
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

    /** 「待补充」+ 本人是负责人 + {@code TICKET_PROCESS}：恰好一格撤回动作。 */
    @Test
    void detailAllowsWithdrawSupplementRequestForCurrentAssigneeOfWaitingForRequester() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_PROCESS");
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));

        TicketDetailResult result = service.detail(TICKET_NO);

        assertThat(result.allowedActions())
                .as("待补充时负责人只应该拿到撤回这一格")
                .containsExactly("withdraw-supplement-request");
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

    /**
     * 两格新动作只属于各自的等待态：{@code PROCESSING} 上即使持有全部相关权限，
     * 也不该多出 {@code withdraw-supplement-request} 或 {@code report-unresolved}。
     */
    @Test
    void detailDoesNotOfferReturnActionsOutsideTheirWaitingStates() {
        stubCurrentUser(IT_USER_ID);
        grant("TICKET_VIEW_QUEUE", "TICKET_CLAIM", "TICKET_PROCESS",
                "TICKET_REQUESTER_ACTION");
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        TicketDetailResult result = service.detail(TICKET_NO);

        assertThat(result.allowedActions())
                .as("处理中只暴露处理与提交解决结果两个动作")
                .containsExactly("add-processing-record", "submit-resolution");
    }

    /** 终态不再暴露任何动作：本人是提交人且持有全部权限、且具备领取资格也不能例外。 */
    @Test
    void detailReturnsNoActionsForTerminalTicket() {
        stubCurrentUser(REQUESTER_ID);
        grant("TICKET_VIEW_OWN", "TICKET_VIEW_QUEUE", "TICKET_VIEW_PARTICIPATED",
                "TICKET_CLAIM", "TICKET_PROCESS", "TICKET_REQUESTER_ACTION");
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

    // ---------- 事务边界 ----------

    /** 三个查询方法都只读：不写库，也不该在事务里拿写锁。 */
    @Test
    void queryMethodsDeclareReadOnlyTransactions() throws NoSuchMethodException {
        assertReadOnly("page", TicketQuery.class);
        assertReadOnly("detail", String.class);
        assertReadOnly("records", String.class, TicketRecordQuery.class);
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
