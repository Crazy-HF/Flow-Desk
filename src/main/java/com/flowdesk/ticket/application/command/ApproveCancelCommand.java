// ApproveCancelCommand.java
package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 当前负责人批准撤销请求：工单进入终态「已取消」。
 */
public record ApproveCancelCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version) {
}