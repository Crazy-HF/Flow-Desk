package com.flowdesk.ticket.domain;

public enum TicketStatus {
    PENDING,
    PROCESSING,
    WAITING_FOR_REQUESTER,
    WAITING_FOR_CONFIRMATION,
    COMPLETED,
    CANCELED,
    CLOSED
}
