package com.flowdesk.ticket.application.result;

/** 状态分组：编码 + 数量。编码与工单状态机一致，顺序由服务层按固定域顺序输出。 */
public record TicketDashboardStatusCountResult(
        String status,
        long count) {
}
