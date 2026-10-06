package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 当前负责人提交解决结果。
 *
 * <p>提交后工单进入 {@code WAITING_FOR_CONFIRMATION}，确认期限由服务端按配置计算，
 * 因此请求体只有版本与解决结论。正文在构造时去除首尾空白，长度校验针对去除后的结果。</p>
 */
public record SubmitResolutionCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version,

        @NotBlank(message = "解决结果不能为空")
        @Size(max = 10000, message = "解决结果最多10000个字符")
        String content) {

    public SubmitResolutionCommand {
        content = content == null ? null : content.strip();
    }
}
