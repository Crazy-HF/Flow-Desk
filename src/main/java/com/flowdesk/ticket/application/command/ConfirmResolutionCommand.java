package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 工单提交人确认问题已解决。
 *
 * <p>确认是单步终态动作，不需要正文：完成方式固定为员工主动确认，
 * 请求体只携带客户端最后读取到的工单版本。</p>
 */
public record ConfirmResolutionCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version) {
}
