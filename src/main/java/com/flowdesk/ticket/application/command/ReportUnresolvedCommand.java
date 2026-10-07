package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 提交人反馈问题未解决。
 *
 * <p>工单从「待确认」退回「处理中」，原负责人保持不变，之前的解决结果作为历史保留
 * （`docs/kickoff.md` 4.5）。未解决原因必填，上限 1000 与其它原因类动作一致。</p>
 *
 * <p>正文在构造时去除首尾空白，长度校验针对去除后的结果。</p>
 */
public record ReportUnresolvedCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version,

        @NotBlank(message = "未解决原因不能为空")
        @Size(max = 1000, message = "未解决原因最长 1000 个字符")
        String reason) {

    public ReportUnresolvedCommand {
        reason = reason == null ? null : reason.strip();
    }
}
