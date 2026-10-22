package com.flowdesk.ticket.application.service.impl;

import com.flowdesk.common.exception.ApiException;
import com.flowdesk.ticket.application.port.CurrentRequesterPort;
import com.flowdesk.ticket.application.port.TicketOperatorRolePort;
import com.flowdesk.ticket.application.port.TicketReadPermissionPort;
import com.flowdesk.ticket.application.query.TicketDashboardQuery;
import com.flowdesk.ticket.application.result.TicketDashboardAssigneeCountResult;
import com.flowdesk.ticket.application.result.TicketDashboardCategoryCountResult;
import com.flowdesk.ticket.application.result.TicketDashboardPriorityCountResult;
import com.flowdesk.ticket.application.result.TicketDashboardResult;
import com.flowdesk.ticket.application.result.TicketDashboardStatusCountResult;
import com.flowdesk.ticket.domain.TicketScope;
import com.flowdesk.ticket.infrastructure.persistence.TicketDashboardCodeCountRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketDashboardRefCountRow;
import com.flowdesk.ticket.mapper.TicketDashboardMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 数据概览服务的业务规则（{@code docs/api-design.md} 8.5）。
 *
 * <p>这一层负责四件事，SQL 聚合本身由 {@code TicketDashboardServiceIT} 在真实 MySQL 上验证：</p>
 * <ol>
 *   <li><b>授权顺序</b>：{@code DASHBOARD_VIEW} → 当前持有 {@code IT_SUPPORT} → 选中范围的查看权限。
 *       系统管理员角色本身不带来任何一条，多角色管理员也必须同时具备 IT 身份；</li>
 *   <li><b>入参收口</b>：范围必填且不接受 {@code REQUESTED_BY_ME}（三个 IT 范围之外没有概览语义）、
 *       分类必须为正整数、创建时间起点不能晚于终点；</li>
 *   <li><b>口径下发</b>：带时区的入参换算成同一时刻的 UTC 本地时间，当前用户只来自认证上下文；</li>
 *   <li><b>组装顺序</b>：状态与优先级按固定域顺序输出且不补零值行，分类与负责人保持 SQL 的
 *       「数量降序、ID 升序、未分配在有 ID 之后」次序。</li>
 * </ol>
 *
 * <p>权限不足时不得触碰 Mapper——否则"没权限"也会先跑一遍全量聚合。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TicketDashboardServiceImplTest {

    private static final long CURRENT_USER_ID = 7L;

    @Mock
    private TicketDashboardMapper ticketDashboardMapper;
    @Mock
    private CurrentRequesterPort currentRequesterPort;
    @Mock
    private TicketReadPermissionPort ticketReadPermissionPort;
    @Mock
    private TicketOperatorRolePort ticketOperatorRolePort;

    private TicketDashboardServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TicketDashboardServiceImpl(
                ticketDashboardMapper,
                currentRequesterPort,
                ticketReadPermissionPort,
                ticketOperatorRolePort);
    }

    // ---------- 入参收口 ----------

    /** 范围是唯一的范围来源，缺失时不能在没有范围约束的情况下聚合全库。 */
    @Test
    void rejectsMissingScopeBeforeAnyQuery() {
        TicketDashboardQuery query = new TicketDashboardQuery();

        assertApiException(() -> service.overview(query),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketDashboardMapper, currentRequesterPort, ticketOperatorRolePort);
    }

    /** {@code REQUESTED_BY_ME} 是员工视角，概览只回答三个 IT 范围的问题。 */
    @Test
    void rejectsRequestedByMeScope() {
        TicketDashboardQuery query = query(TicketScope.REQUESTED_BY_ME);

        assertApiException(() -> service.overview(query),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketDashboardMapper);
    }

    @Test
    void rejectsNonPositiveCategoryId() {
        TicketDashboardQuery query = query(TicketScope.PENDING_QUEUE);
        query.setCategoryId(0L);

        assertApiException(() -> service.overview(query),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketDashboardMapper);
    }

    @Test
    void rejectsCreatedRangeThatRunsBackwards() {
        TicketDashboardQuery query = query(TicketScope.PENDING_QUEUE);
        query.setCreatedFrom(OffsetDateTime.parse("2026-10-06T00:00:00Z"));
        query.setCreatedTo(OffsetDateTime.parse("2026-10-01T00:00:00Z"));

        assertApiException(() -> service.overview(query),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketDashboardMapper);
    }

    // ---------- 授权 ----------

    /**
     * 没有 {@code DASHBOARD_VIEW} 时连 IT 身份都不必查：{@code SYSTEM_ADMIN} 内置权限集里没有它，
     * 纯管理员因此拿不到任何统计。
     */
    @Test
    void forbidsWithoutDashboardViewPermission() {
        stubCurrentUser();
        stubPermission("DASHBOARD_VIEW", false);

        assertApiException(() -> service.overview(query(TicketScope.PENDING_QUEUE)),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");

        verify(ticketOperatorRolePort, never()).hasActiveItSupportRole(anyLong());
        verifyNoInteractions(ticketDashboardMapper);
    }

    /**
     * 有 {@code DASHBOARD_VIEW} 但没有 {@code IT_SUPPORT} 身份时同样 403。
     *
     * <p>这是本接口最容易被误解的一格：权限码可以由任意角色授予，但概览统计的是 IT 工单范围，
     * 因此身份必须由 IAM 侧的可信数据确认，不能只看权限集合。</p>
     */
    @Test
    void forbidsWithoutItSupportRoleEvenWhenThePermissionIsGranted() {
        stubCurrentUser();
        stubPermission("DASHBOARD_VIEW", true);
        when(ticketOperatorRolePort.hasActiveItSupportRole(CURRENT_USER_ID)).thenReturn(false);

        assertApiException(() -> service.overview(query(TicketScope.PENDING_QUEUE)),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");

        verifyNoInteractions(ticketDashboardMapper);
    }

    /** 待受理范围要求队列查看权限；缺少时不能退化成"空统计"。 */
    @Test
    void forbidsQueueScopeWithoutQueuePermission() {
        stubAuthorizedItUser();
        stubPermission("TICKET_VIEW_QUEUE", false);

        assertApiException(() -> service.overview(query(TicketScope.PENDING_QUEUE)),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");

        verifyNoInteractions(ticketDashboardMapper);
    }

    /** 另外两个范围共用参与查看权限；缺权限时同样 403。 */
    @ParameterizedTest(name = "{0} 缺少 TICKET_VIEW_PARTICIPATED 时 403")
    @EnumSource(value = TicketScope.class, names = {"ASSIGNED_TO_ME", "PARTICIPATED_BY_ME"})
    void forbidsParticipatedScopesWithoutParticipatedPermission(TicketScope scope) {
        stubAuthorizedItUser();
        stubPermission("TICKET_VIEW_PARTICIPATED", false);

        assertApiException(() -> service.overview(query(scope)),
                HttpStatus.FORBIDDEN, "ACCESS_DENIED");

        verifyNoInteractions(ticketDashboardMapper);
    }

    // ---------- 口径下发 ----------

    /** 当前用户只来自认证上下文，时间换算成同一时刻的 UTC，分类原样下发。 */
    @Test
    void passesAuthenticatedUserUtcBoundsAndCategoryToMapper() {
        stubAuthorizedItUser();
        stubPermission("TICKET_VIEW_QUEUE", true);
        stubEmptyAggregation();

        TicketDashboardQuery query = query(TicketScope.PENDING_QUEUE);
        query.setCreatedFrom(OffsetDateTime.parse("2026-10-06T08:00:00+08:00"));
        query.setCreatedTo(OffsetDateTime.parse("2026-10-07T00:00:00+08:00"));
        query.setCategoryId(7L);

        service.overview(query);

        ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(ticketDashboardMapper).countScoped(
                eq(CURRENT_USER_ID), eq(query), fromCaptor.capture(), toCaptor.capture());

        assertThat(fromCaptor.getValue()).as("+08:00 的 08:00 是 UTC 的 00:00")
                .isEqualTo(LocalDateTime.of(2026, 10, 6, 0, 0));
        assertThat(toCaptor.getValue()).isEqualTo(LocalDateTime.of(2026, 10, 6, 16, 0));

        // 四组与总数必须来自同一个查询条件：同一个 query 对象、同样的两个边界
        verify(ticketDashboardMapper).countByStatus(
                eq(CURRENT_USER_ID), eq(query), eq(fromCaptor.getValue()), eq(toCaptor.getValue()));
        verify(ticketDashboardMapper).countByPriority(
                eq(CURRENT_USER_ID), eq(query), eq(fromCaptor.getValue()), eq(toCaptor.getValue()));
        verify(ticketDashboardMapper).countByCategory(
                eq(CURRENT_USER_ID), eq(query), eq(fromCaptor.getValue()), eq(toCaptor.getValue()));
        verify(ticketDashboardMapper).countByAssignee(
                eq(CURRENT_USER_ID), eq(query), eq(fromCaptor.getValue()), eq(toCaptor.getValue()));
    }

    /** 没有时间条件时不制造"近 30 天"之类的隐含窗口。 */
    @Test
    void omitsCreationBoundsWhenTheyAreNotProvided() {
        stubAuthorizedItUser();
        stubPermission("TICKET_VIEW_QUEUE", true);
        stubEmptyAggregation();

        service.overview(query(TicketScope.PENDING_QUEUE));

        verify(ticketDashboardMapper).countScoped(
                eq(CURRENT_USER_ID), any(TicketDashboardQuery.class), isNull(), isNull());
    }

    // ---------- 组装 ----------

    /**
     * 状态按工单状态机顺序、优先级按高→中→低输出，只返回命中的分组。
     *
     * <p>SQL 的 {@code GROUP BY} 次序由存储引擎决定，界面上的"先看哪一行"必须由服务层固定，
     * 否则同一份数据在不同执行计划下会换位置。</p>
     */
    @Test
    void ordersStatusAndPriorityByFixedDomainOrderAndOmitsEmptyGroups() {
        stubAuthorizedItUser();
        stubPermission("TICKET_VIEW_QUEUE", true);
        when(ticketDashboardMapper.countScoped(anyLong(), any(), any(), any())).thenReturn(4L);
        when(ticketDashboardMapper.countByStatus(anyLong(), any(), any(), any())).thenReturn(List.of(
                codeCount("CLOSED", 1),
                codeCount("PROCESSING", 2),
                codeCount("PENDING", 1)));
        when(ticketDashboardMapper.countByPriority(anyLong(), any(), any(), any())).thenReturn(List.of(
                codeCount("LOW", 1),
                codeCount("HIGH", 3)));
        when(ticketDashboardMapper.countByCategory(anyLong(), any(), any(), any()))
                .thenReturn(List.of());
        when(ticketDashboardMapper.countByAssignee(anyLong(), any(), any(), any()))
                .thenReturn(List.of());

        TicketDashboardResult result = service.overview(query(TicketScope.PENDING_QUEUE));

        assertThat(result.total()).isEqualTo(4);
        assertThat(result.byStatus())
                .extracting(TicketDashboardStatusCountResult::status)
                .as("PENDING 在前、CLOSED 在后，未命中的四个状态不补零值行")
                .containsExactly("PENDING", "PROCESSING", "CLOSED");
        assertThat(result.byStatus())
                .extracting(TicketDashboardStatusCountResult::count)
                .containsExactly(1L, 2L, 1L);
        assertThat(result.byPriority())
                .extracting(TicketDashboardPriorityCountResult::priority)
                .as("优先级固定 HIGH → MEDIUM → LOW")
                .containsExactly("HIGH", "LOW");
    }

    /**
     * 分类与负责人保持 SQL 给出的次序（数量降序、ID 升序、未分配在有 ID 之后），服务层不再重排。
     *
     * <p>未分配那一组的响应元素**没有 {@code assignee} 字段**（项目 {@code non_null} 约定），
     * 因此这里构造的是 {@code assignee = null} 的结果对象。</p>
     */
    @Test
    void keepsCategoryAndAssigneeOrderFromSqlAndRepresentsUnassignedAsNullAssignee() {
        stubAuthorizedItUser();
        stubPermission("TICKET_VIEW_PARTICIPATED", true);
        when(ticketDashboardMapper.countScoped(anyLong(), any(), any(), any())).thenReturn(3L);
        when(ticketDashboardMapper.countByStatus(anyLong(), any(), any(), any()))
                .thenReturn(List.of(codeCount("PROCESSING", 3)));
        when(ticketDashboardMapper.countByPriority(anyLong(), any(), any(), any()))
                .thenReturn(List.of(codeCount("MEDIUM", 3)));
        when(ticketDashboardMapper.countByCategory(anyLong(), any(), any(), any())).thenReturn(List.of(
                refCount(7L, "办公设备", 2),
                refCount(3L, "网络", 1)));
        when(ticketDashboardMapper.countByAssignee(anyLong(), any(), any(), any())).thenReturn(List.of(
                refCount(12L, "李工", 2),
                refCount(null, null, 1)));

        TicketDashboardResult result = service.overview(query(TicketScope.ASSIGNED_TO_ME));

        assertThat(result.byCategory())
                .extracting(row -> row.category().id())
                .containsExactly(7L, 3L);
        assertThat(result.byCategory())
                .extracting(row -> row.category().name())
                .containsExactly("办公设备", "网络");
        assertThat(result.byAssignee())
                .extracting(TicketDashboardAssigneeCountResult::count)
                .containsExactly(2L, 1L);
        assertThat(result.byAssignee().get(0).assignee().displayName()).isEqualTo("李工");
        assertThat(result.byAssignee().get(1).assignee())
                .as("未分配组没有 assignee，序列化后该键不出现")
                .isNull();
    }

    /** 没有命中时总数 0、四组都是空数组，而不是缺键。 */
    @Test
    void returnsZeroTotalAndEmptyGroupsWhenNothingMatches() {
        stubAuthorizedItUser();
        stubPermission("TICKET_VIEW_PARTICIPATED", true);
        stubEmptyAggregation();

        TicketDashboardResult result = service.overview(query(TicketScope.PARTICIPATED_BY_ME));

        assertThat(result.scope()).isEqualTo("PARTICIPATED_BY_ME");
        assertThat(result.total()).isZero();
        assertThat(result.byStatus()).isEmpty();
        assertThat(result.byPriority()).isEmpty();
        assertThat(result.byCategory()).isEmpty();
        assertThat(result.byAssignee()).isEmpty();
    }

    /** 总数只来自独立的 {@code COUNT(*)}，不由分组求和推导——两组数各有来源才能互相校验。 */
    @Test
    void takesTotalFromItsOwnCountQuery() {
        stubAuthorizedItUser();
        stubPermission("TICKET_VIEW_QUEUE", true);
        when(ticketDashboardMapper.countScoped(anyLong(), any(), any(), any())).thenReturn(9L);
        when(ticketDashboardMapper.countByStatus(anyLong(), any(), any(), any()))
                .thenReturn(List.of(codeCount("PENDING", 9)));

        assertThat(service.overview(query(TicketScope.PENDING_QUEUE)).total()).isEqualTo(9);
    }

    // ---------- 辅助 ----------

    private TicketDashboardQuery query(TicketScope scope) {
        TicketDashboardQuery query = new TicketDashboardQuery();
        query.setScope(scope);
        return query;
    }

    private void stubCurrentUser() {
        when(currentRequesterPort.currentUserId()).thenReturn(CURRENT_USER_ID);
    }

    private void stubPermission(String authority, boolean granted) {
        when(ticketReadPermissionPort.hasAuthority(authority)).thenReturn(granted);
    }

    /** 已通过三层授权的 IT 用户；{@code DASHBOARD_VIEW} 与 IT 身份默认成立。 */
    private void stubAuthorizedItUser() {
        stubCurrentUser();
        stubPermission("DASHBOARD_VIEW", true);
        when(ticketOperatorRolePort.hasActiveItSupportRole(CURRENT_USER_ID)).thenReturn(true);
    }

    private void stubEmptyAggregation() {
        when(ticketDashboardMapper.countScoped(anyLong(), any(), any(), any())).thenReturn(0L);
        when(ticketDashboardMapper.countByStatus(anyLong(), any(), any(), any())).thenReturn(List.of());
        when(ticketDashboardMapper.countByPriority(anyLong(), any(), any(), any())).thenReturn(List.of());
        when(ticketDashboardMapper.countByCategory(anyLong(), any(), any(), any())).thenReturn(List.of());
        when(ticketDashboardMapper.countByAssignee(anyLong(), any(), any(), any())).thenReturn(List.of());
    }

    private static TicketDashboardCodeCountRow codeCount(String code, long total) {
        TicketDashboardCodeCountRow row = new TicketDashboardCodeCountRow();
        row.setCode(code);
        row.setTotal(total);
        return row;
    }

    private static TicketDashboardRefCountRow refCount(Long refId, String displayName, long total) {
        TicketDashboardRefCountRow row = new TicketDashboardRefCountRow();
        row.setRefId(refId);
        row.setDisplayName(displayName);
        row.setTotal(total);
        return row;
    }

    private static void assertApiException(
            ThrowingCallable invocation, HttpStatus status, String code) {
        ApiException exception = catchThrowableOfType(invocation, ApiException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.status()).isEqualTo(status);
        assertThat(exception.code()).isEqualTo(code);
    }
}
