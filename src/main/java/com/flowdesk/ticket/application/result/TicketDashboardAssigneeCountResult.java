package com.flowdesk.ticket.application.result;

/**
 * 负责人分组：当前（或终态时保留的最后）负责人 + 数量。
 *
 * <p>未分配工单的 {@code assignee} 为 {@code null}，按项目 {@code non_null} 序列化约定
 * 该键不出现在响应里——界面据此显示"未分配"，不需要额外的布尔字段。</p>
 */
public record TicketDashboardAssigneeCountResult(
        TicketUserSummaryResult assignee,
        long count) {
}
