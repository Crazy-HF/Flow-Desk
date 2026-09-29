package com.flowdesk.ticket.application.query;

import jakarta.validation.constraints.NotBlank;

public record TicketDetailQuery(
        @NotBlank(message = "工单编号不能为空")
        String ticketNo) {
}
