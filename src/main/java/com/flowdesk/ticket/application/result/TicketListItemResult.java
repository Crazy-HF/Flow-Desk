package com.flowdesk.ticket.application.result;

import java.time.OffsetDateTime;

public record TicketListItemResult(
        String ticketNo,
        String title,
        TicketCategorySummaryResult category,
        String priority,
        String status,
        TicketUserSummaryResult requester,
        TicketUserSummaryResult assignee,
        OffsetDateTime actionDeadlineAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        Long version) {
}
