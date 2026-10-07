package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 当前负责人撤回补充请求。
 *
 * <p>撤回后工单从「待补充」回到「处理中」，原补充期限立即失效（`docs/kickoff.md` 4.11）。
 * 撤回原因必填，上限 1000 与其它原因类动作一致（`docs/api-design.md` 6.3）。</p>
 *
 * <p>正文在构造时去除首尾空白，长度校验针对去除后的结果，与其它正文/原因命令保持一致。</p>
 */
public record WithdrawSupplementRequestCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version,

        @NotBlank(message = "撤回原因不能为空")
        @Size(max = 1000, message = "撤回原因最长 1000 个字符")
        String reason) {

    public WithdrawSupplementRequestCommand {
        reason = reason == null ? null : reason.strip();
    }
}
