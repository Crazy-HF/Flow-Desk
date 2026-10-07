package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 当前负责人把工单直接转交给另一名 IT 支持人员。
 *采用直接转交：接收方不需要再次确认，转交成功后新负责人立即承担处理责任
 *工单状态不变，转交前后都保持原有的「处理中」或「待补充」，
 */
public record TransferCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version,

        @NotNull(message = "新负责人不能为空")
        @Positive(message = "新负责人ID必须为正数")
        Long newAssigneeId,

        @NotBlank(message = "转交原因不能为空")
        @Size(max = 1000, message = "转交原因最长 1000 个字符")
        String reason) {

    public TransferCommand {
        reason = reason == null ? null : reason.strip();
    }
}