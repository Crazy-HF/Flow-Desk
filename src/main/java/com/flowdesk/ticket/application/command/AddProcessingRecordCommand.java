package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 当前负责人追加处理记录。
 *
 * <p>本动作不改变状态与负责人，只递增工单版本与记录序号，因此请求体只有版本与处理正文。
 * 正文在构造时去除首尾空白，长度校验针对去除后的结果，与其他正文命令保持一致。</p>
 */
public record AddProcessingRecordCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version,

        @NotBlank(message = "处理内容不能为空")
        @Size(max = 10000, message = "处理内容最多10000个字符")
        String content) {

    public AddProcessingRecordCommand {
        content = content == null ? null : content.strip();
    }
}
