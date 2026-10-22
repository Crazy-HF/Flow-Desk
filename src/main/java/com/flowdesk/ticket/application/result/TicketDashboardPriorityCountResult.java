package com.flowdesk.ticket.application.result;

/** 优先级分组：编码 + 数量，输出顺序固定为 HIGH → MEDIUM → LOW。 */
public record TicketDashboardPriorityCountResult(
        String priority,
        long count) {
}
