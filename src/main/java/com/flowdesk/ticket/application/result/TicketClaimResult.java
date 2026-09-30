package com.flowdesk.ticket.application.result;

import java.time.OffsetDateTime;

public record TicketClaimResult(
        String ticketNo,
        String status,
        TicketUserSummaryResult assignee,
        OffsetDateTime actionDeadlineAt,
        Long version,
        OffsetDateTime actionTime
) {
}
