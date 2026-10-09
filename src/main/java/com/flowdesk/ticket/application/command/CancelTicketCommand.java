package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 提交人直接撤销自己的工单（`docs/kickoff.md` 4.7）。
 *
 * <p><b>2026-10-08 规则变更</b>：只剩「待受理」可以这样直接撤销——那里没有负责人，
 * 没有人需要批准。处理中、待补充、待确认三种状态改走两阶段：提交人
 * {@code request-cancel}，当前负责人 {@code approve-cancel} / {@code reject-cancel}。
 * 直接撤销的入口没有收窄"提交人"这个身份，收窄的是允许状态。</p>
 *
 * <p>撤销原因写入不可变时间线的 CANCELLATION 记录，{@code ticket} 表没有撤销原因列。</p>
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
