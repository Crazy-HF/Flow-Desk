package com.flowdesk.ticket.application.result;

/** 历史中实际发送给模型的五项输入快照。 */
public record TicketAiInputSnapshotResult(
        String title,
        String description,
        String category,
        String priority,
        String status) {
}
