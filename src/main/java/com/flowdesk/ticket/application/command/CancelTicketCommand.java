package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 提交人撤销自己的工单（`docs/kickoff.md` 4.7）。
 *
 * <p>四种非终态（待受理、处理中、待补充、待确认）都可以撤销，进入「已取消」终态；
 * v1 不支持恢复，员工仍需处理时应新建工单。撤销原因写入不可变时间线的 CANCELLATION 记录，
 * {@code ticket} 表没有独立的撤销原因列。</p>
 */
public record CancelTicketCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version,

        @NotBlank(message = "撤销原因不能为空")
        @Size(max = 1000, message = "撤销原因最长 1000 个字符")
        String reason) {

    public CancelTicketCommand {
        reason = reason == null ? null : reason.strip();
    }
}
