package com.flowdesk.ticket.application.result;

import java.time.OffsetDateTime;

public record TicketCreatedResult(
        String ticketNo,
        String status,
        Long version,
        OffsetDateTime createdAt
) {
}