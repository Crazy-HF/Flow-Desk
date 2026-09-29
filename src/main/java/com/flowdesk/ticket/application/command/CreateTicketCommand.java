package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateTicketCommand(
        @NotBlank
        @Pattern(
                regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{12}$",
                message = "提交键必须是 UUID")
        String submissionKey,

        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题最多200个字符")
        String title,

        @NotBlank(message = "描述不能为空")
        @Size(max = 10000, message = "描述最多10000个字符")
        String description,

        @NotNull(message = "分类不能为空")
        @Positive(message = "分类ID必须为正数")
        Long categoryId,

        @NotBlank(message = "优先级不能为空")
        @Pattern(
                regexp = "LOW|MEDIUM|HIGH",
                message = "优先级必须是LOW、MEDIUM或HIGH")
        String priority
) {
    public CreateTicketCommand {
        title = title == null ? null : title.strip();
        description = description == null ? null : description.strip();
    }
}