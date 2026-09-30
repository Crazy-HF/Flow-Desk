package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 领取请求只携带客户端最后读取到的工单版本。 */
public record ClaimTicketCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version) {
}
