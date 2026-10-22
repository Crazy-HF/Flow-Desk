package com.flowdesk.ticket.application.result;

import java.util.List;

/**
 * 数据概览的响应体（{@code docs/api-design.md} 8.5，{@code GET /fd/v1/dashboard/tickets}）。
 *
 * <p>四组都基于**同一个**可见工单集合 {@code S} 聚合，因此每组求和都等于 {@link #total}；
 * 只返回命中的分组，不补零值行。终态工单使用当前状态、优先级、分类与保留的最后负责人，
 * 不从时间线重算历史分类或历史负责人。</p>
 *
 * <p>{@code total} 与四组各有独立来源（一次 {@code COUNT(*)} 加四条 {@code GROUP BY}），
 * 不互相推导——两组数各自算出来才对得上，是"统计口径一致"的可检查形式。</p>
 */
public record TicketDashboardResult(
        String scope,
        long total,
        List<TicketDashboardStatusCountResult> byStatus,
        List<TicketDashboardPriorityCountResult> byPriority,
        List<TicketDashboardCategoryCountResult> byCategory,
        List<TicketDashboardAssigneeCountResult> byAssignee) {
}
