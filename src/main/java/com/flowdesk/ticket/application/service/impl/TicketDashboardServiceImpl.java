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
import com.flowdesk.ticket.application.result.TicketCategorySummaryResult;
import com.flowdesk.ticket.application.result.TicketUserSummaryResult;
import com.flowdesk.ticket.application.service.TicketDashboardService;
import com.flowdesk.ticket.domain.TicketPriority;
import com.flowdesk.ticket.domain.TicketScope;
import com.flowdesk.ticket.domain.TicketStatus;
import com.flowdesk.ticket.infrastructure.persistence.TicketDashboardCodeCountRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketDashboardRefCountRow;
import com.flowdesk.ticket.mapper.TicketDashboardMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 数据概览（{@code docs/api-design.md} 8.5）。
 *
 * <p>一次请求只服务一个既有 IT 范围：范围是必填枚举，不做"全部可见"的并集——
 * 「我负责的」与「我参与的」有重叠，合并需要另一套去重与授权规则，也无法与列表逐项对账。</p>
 *
 * <p>授权顺序（服务层部分）：{@code DASHBOARD_VIEW} 已在控制器的方法级授权里校验；
 * 这里再校验**当前持有 IT 身份**与**选中范围的查看权限**。两者都不足时统一
 * {@code 403/ACCESS_DENIED}——它们都是"你的身份不覆盖这个接口"，与"工单不存在"无关，
 * 因此不使用 {@code 404/TICKET_NOT_FOUND}，也不回显任何统计结果。</p>
 *
 * <p>五个聚合查询在同一个 {@code @Transactional(readOnly = true)} 事务里执行：MySQL 默认的
 * {@code REPEATABLE READ} 在第一次一致性读时建立快照，因此并发修改期间同一次响应的总数与四组
 * 不会短时不符。缓存、预计算表与后台刷新任务都不在本版范围内——当前规模没有证据支持它们。</p>
 */
@Service
public class TicketDashboardServiceImpl implements TicketDashboardService {

    /** 概览权限：由控制器的方法级授权先拦一次，服务层仍独立判定（服务可能被其他入口调用）。 */
    private static final String DASHBOARD_VIEW = "DASHBOARD_VIEW";
    /** 待受理范围的查看权限，与 {@code GET /fd/v1/tickets?scope=PENDING_QUEUE} 同一条。 */
    private static final String TICKET_VIEW_QUEUE = "TICKET_VIEW_QUEUE";
    /** 「我负责的」与「我参与的」共用的查看权限。 */
    private static final String TICKET_VIEW_PARTICIPATED = "TICKET_VIEW_PARTICIPATED";

    /**
     * 状态输出顺序：工单状态机顺序，直接取枚举声明顺序。
     *
     * <p>不从 SQL 的 {@code GROUP BY} 结果顺序推断——那是存储引擎的选择，换执行计划就会变。</p>
     */
    private static final List<String> STATUS_ORDER = Arrays.stream(TicketStatus.values())
            .map(Enum::name)
            .toList();

    /**
     * 优先级输出顺序：高 → 中 → 低。
     *
     * <p>刻意不用 {@code TicketPriority.values()}：枚举按 LOW/MEDIUM/HIGH 声明（升序语义），
     * 而概览要按"最需要关注的在最前"排列，两者是不同的决定，不能共用一处顺序。</p>
     */
    private static final List<String> PRIORITY_ORDER = List.of(
            TicketPriority.HIGH.name(),
            TicketPriority.MEDIUM.name(),
            TicketPriority.LOW.name());

    private final TicketDashboardMapper ticketDashboardMapper;
    private final CurrentRequesterPort currentRequesterPort;
    private final TicketReadPermissionPort ticketReadPermissionPort;
    private final TicketOperatorRolePort ticketOperatorRolePort;

    public TicketDashboardServiceImpl(
            TicketDashboardMapper ticketDashboardMapper,
            CurrentRequesterPort currentRequesterPort,
            TicketReadPermissionPort ticketReadPermissionPort,
            TicketOperatorRolePort ticketOperatorRolePort) {
        this.ticketDashboardMapper = ticketDashboardMapper;
        this.currentRequesterPort = currentRequesterPort;
        this.ticketReadPermissionPort = ticketReadPermissionPort;
        this.ticketOperatorRolePort = ticketOperatorRolePort;
    }

    @Override
    @Transactional(readOnly = true)
    public TicketDashboardResult overview(TicketDashboardQuery query) {
        // 1. 参数收口先于任何查询：范围缺失或使用员工视角时不能退化成"统计全库"
        validate(query);

        // 2. 当前用户只来自认证上下文，不接受 userId / assigneeId / teamId 之类的指定他人参数
        long currentUserId = currentRequesterPort.currentUserId();

        // 3. 三层授权：概览权限 → IT 身份 → 选中范围的查看权限
        requireDashboardAccess(currentUserId, query.getScope());

        // 4. 时间统一换算成同一时刻的 UTC 本地时间；不传即不限制，不暗设默认窗口
        LocalDateTime createdFrom = toUtc(query.getCreatedFrom());
        LocalDateTime createdTo = toUtc(query.getCreatedTo());

        // 5. 同一个只读事务里的五个查询共享一个一致性快照
        long total = ticketDashboardMapper.countScoped(currentUserId, query, createdFrom, createdTo);
        List<TicketDashboardStatusCountResult> byStatus = toStatusCounts(
                ticketDashboardMapper.countByStatus(currentUserId, query, createdFrom, createdTo));
        List<TicketDashboardPriorityCountResult> byPriority = toPriorityCounts(
                ticketDashboardMapper.countByPriority(currentUserId, query, createdFrom, createdTo));
        List<TicketDashboardCategoryCountResult> byCategory = toCategoryCounts(
                ticketDashboardMapper.countByCategory(currentUserId, query, createdFrom, createdTo));
        List<TicketDashboardAssigneeCountResult> byAssignee = toAssigneeCounts(
                ticketDashboardMapper.countByAssignee(currentUserId, query, createdFrom, createdTo));

        return new TicketDashboardResult(
                query.getScope().name(),
                total,
                byStatus,
                byPriority,
                byCategory,
                byAssignee);
    }

    /**
     * 参数校验。
     *
     * <p>控制器上的 {@code @Valid} 已覆盖同一批规则；服务层再判一次，是因为"没有范围就统计全库"
     * 不能靠调用方自觉——这个服务不只在 HTTP 入口被调用时才有意义，而漏掉范围校验的后果是
     * 一次越权聚合。与 {@code TicketQueryServiceImpl.page} 的 {@code scope == null} 判定同一风格。</p>
     */
    private void validate(TicketDashboardQuery query) {
        if (query == null || query.getScope() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "查询范围不能为空");
        }

        if (query.getScope() == TicketScope.REQUESTED_BY_ME) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "数据概览只支持 PENDING_QUEUE、ASSIGNED_TO_ME 与 PARTICIPATED_BY_ME");
        }

        if (query.getCategoryId() != null && query.getCategoryId() <= 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "分类标识必须为正整数");
        }

        if (query.getCreatedFrom() != null && query.getCreatedTo() != null
                && query.getCreatedFrom().toInstant().isAfter(query.getCreatedTo().toInstant())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "创建时间起点不能晚于终点");
        }
    }

    /**
     * 三层授权。
     *
     * <p>{@code SYSTEM_ADMIN} 本身不带来任何一条：它的内置权限集里没有 {@code DASHBOARD_VIEW}，
     * 也没有 {@code IT_SUPPORT} 角色。多角色管理员只有同时具备 IT 身份与具体范围的查看权限时，
     * 才能看到对应统计。</p>
     */
    private void requireDashboardAccess(long currentUserId, TicketScope scope) {
        if (!ticketReadPermissionPort.hasAuthority(DASHBOARD_VIEW)) {
            throw accessDenied();
        }

        // IT 身份必须查 IAM 的可信数据：认证对象里只有用户标识与会话信息，没有角色
        if (!ticketOperatorRolePort.hasActiveItSupportRole(currentUserId)) {
            throw accessDenied();
        }

        String scopePermission = scope == TicketScope.PENDING_QUEUE
                ? TICKET_VIEW_QUEUE
                : TICKET_VIEW_PARTICIPATED;
        if (!ticketReadPermissionPort.hasAuthority(scopePermission)) {
            throw accessDenied();
        }
    }

    private ApiException accessDenied() {
        return new ApiException(
                HttpStatus.FORBIDDEN,
                "ACCESS_DENIED",
                "当前身份无权查看数据概览");
    }

    /**
     * 状态分组：按状态机顺序输出，只保留命中的分组。
     *
     * <p>不补零值行——"待受理 0 张"和"没有这个分组"对使用者是同一件事，补零只会让四个组
     * 永远等长、把真正的空结果藏起来。</p>
     */
    private List<TicketDashboardStatusCountResult> toStatusCounts(
            List<TicketDashboardCodeCountRow> rows) {
        Map<String, Long> counts = indexByCode(rows);
        return STATUS_ORDER.stream()
                .filter(counts::containsKey)
                .map(status -> new TicketDashboardStatusCountResult(status, counts.get(status)))
                .toList();
    }

    /** 优先级分组：固定 HIGH → MEDIUM → LOW。 */
    private List<TicketDashboardPriorityCountResult> toPriorityCounts(
            List<TicketDashboardCodeCountRow> rows) {
        Map<String, Long> counts = indexByCode(rows);
        return PRIORITY_ORDER.stream()
                .filter(counts::containsKey)
                .map(priority -> new TicketDashboardPriorityCountResult(priority, counts.get(priority)))
                .toList();
    }

    /**
     * 分类分组：保持 SQL 的"数量降序、ID 升序"。
     *
     * <p>排序键里含 {@code COUNT(*)}，Java 侧没有与 SQL 完全等价的输入（行里只有数量），
     * 因此这里不重排，只做投影。</p>
     */
    private List<TicketDashboardCategoryCountResult> toCategoryCounts(
            List<TicketDashboardRefCountRow> rows) {
        return rows.stream()
                .map(row -> new TicketDashboardCategoryCountResult(
                        new TicketCategorySummaryResult(row.getRefId(), row.getDisplayName()),
                        countOf(row)))
                .toList();
    }

    /** 负责人分组：{@code refId} 为空即"未分配"，结果对象不带 {@code assignee}。 */
    private List<TicketDashboardAssigneeCountResult> toAssigneeCounts(
            List<TicketDashboardRefCountRow> rows) {
        return rows.stream()
                .map(row -> new TicketDashboardAssigneeCountResult(
                        row.getRefId() == null
                                ? null
                                : new TicketUserSummaryResult(row.getRefId(), row.getDisplayName()),
                        countOf(row)))
                .toList();
    }

    /** 编码 → 数量；同一编码不会出现两行（SQL 已 {@code GROUP BY}）。 */
    private Map<String, Long> indexByCode(List<TicketDashboardCodeCountRow> rows) {
        return rows.stream()
                .filter(row -> row.getCode() != null)
                .collect(Collectors.toMap(
                        TicketDashboardCodeCountRow::getCode,
                        row -> row.getTotal() == null ? 0L : row.getTotal(),
                        (first, second) -> first));
    }

    private long countOf(TicketDashboardRefCountRow row) {
        return row.getTotal() == null ? 0L : row.getTotal();
    }

    /**
     * 保持同一时刻，将带时区输入转换为 UTC 本地时间。
     *
     * <p>与工单列表 {@code TicketQueryServiceImpl.toUtc} 同一口径：库里的 {@code created_at}
     * 是 UTC，两侧都按 UTC 比较，边界不会整体偏移。</p>
     */
    private LocalDateTime toUtc(OffsetDateTime value) {
        return value == null
                ? null
                : value.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
