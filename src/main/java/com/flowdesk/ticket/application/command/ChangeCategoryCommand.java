package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 当前负责人调整工单分类。
 「处理中」与「待补充」两个状态都可以调整，状态与负责人不变，只替换当前分类并追加调整记录
 */
public record ChangeCategoryCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version,

        @NotNull(message = "分类不能为空")
        @Positive(message = "分类ID必须为正数")
        Long categoryId,

        @NotBlank(message = "调整原因不能为空")
        @Size(max = 1000, message = "调整原因最长 1000 个字符")
        String reason) {

    public ChangeCategoryCommand {
        reason = reason == null ? null : reason.strip();
    }
}