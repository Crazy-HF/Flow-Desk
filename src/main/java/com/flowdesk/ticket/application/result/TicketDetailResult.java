package com.flowdesk.ticket.application.result;

import java.time.OffsetDateTime;
import java.util.List;

public record TicketDetailResult(
        String ticketNo,
        String title,
        String description,
        TicketCategorySummaryResult category,
        String priority,
        String status,
        TicketUserSummaryResult requester,
        TicketUserSummaryResult assignee,
        OffsetDateTime actionDeadlineAt,
        Long version,
        String completionMethod,
        String closeMethod,
        String closeReason,
        OffsetDateTime endedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<String> allowedActions) {
}
