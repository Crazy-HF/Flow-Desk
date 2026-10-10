package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 提交人发起撤销请求（{@code docs/kickoff.md} 4.7、{@code docs/implementation-plan.md} 9.3 决策记录）。
 *
 * <p>只允许在「处理中」「待补充」「待确认」发起——这三个状态都已经有人负责，
 * 单方面终止要先取得当前负责人同意。待受理没有负责人，走 {@code CancelTicketCommand} 直接取消。</p>
 *
 * <p>请求期间工单状态不变，说明因此需要落在 {@code ticket} 上（与 CLOSURE 的 close_reason 同模式），
 * 而不是只写进时间线：负责人打开详情时要先看到"为什么"，才谈得上批准或拒绝。</p>
 */
public record RequestCancelCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version,

        @NotBlank(message = "撤销请求说明不能为空")
        @Size(max = 1000, message = "撤销请求说明最长 1000 个字符")
        String reason) {

    public RequestCancelCommand {
        reason = reason == null ? null : reason.strip();
    }
}
