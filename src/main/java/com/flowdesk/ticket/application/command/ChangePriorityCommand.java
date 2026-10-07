package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 当前负责人调整工单优先级。
 * 允许状态与分类调整完全相同（「处理中」与「待补充」），状态与负责人不变，只替换优先级并追加调整记录
 */
public record ChangePriorityCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version,

        @NotBlank(message = "优先级不能为空")
        @Pattern(
                regexp = "LOW|MEDIUM|HIGH",
                message = "优先级必须是LOW、MEDIUM或HIGH")
        String priority,

        @NotBlank(message = "调整原因不能为空")
        @Size(max = 1000, message = "调整原因最长 1000 个字符")
        String reason) {

    public ChangePriorityCommand {
        reason = reason == null ? null : reason.strip();
    }
}