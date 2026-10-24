package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/** 负责人确认后的生成命令；只允许自定义标题、描述和请求标识、工单版本。 */
public record GenerateTicketAiSuggestionCommand(
        @NotBlank(message = "请求标识不能为空")
        @Pattern(
                regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                        + "[0-9a-fA-F]{12}$",
                message = "请求标识必须是 UUID")
        String requestId,

        @NotNull(message = "工单版本不能为空")
        @PositiveOrZero(message = "工单版本不能为负数")
        Long version,

        @NotBlank(message = "标题不能为空")
        @Size(max = 200, message = "标题最多200个字符")
        String title,

        @NotBlank(message = "描述不能为空")
        @Size(max = 10000, message = "描述最多10000个字符")
        String description) {

    /** 规范化请求标识和负责人确认的文本，再由字段约束校验格式与长度。 */
    public GenerateTicketAiSuggestionCommand {
        requestId = requestId == null
                ? null
                : requestId.strip().toLowerCase(Locale.ROOT);
        title = title == null ? null : title.strip();
        description = description == null ? null : description.strip();
    }
}
