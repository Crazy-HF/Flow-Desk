package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 提交人补充信息。
 *
 * <p>工单从「待补充」回到「处理中」，原补充期限立即失效，原负责人继续处理
 * （`docs/kickoff.md` 4.11）。</p>
 *
 * <p><b>这是 multipart 请求里的 JSON 载体</b>：接口按 `docs/api-design.md` 6.4 使用
 * {@code multipart/form-data}，{@code ticket} 部分携带本类型。附件（{@code files} 部分）
 * 属完整版 backlog 第 2 项，本版本出现文件部分直接返回 {@code 400/VALIDATION_FAILED}；
 * 附件落地时只需为 multipart 增加 {@code files} 部分并在此登记附件标识，不必改这个类型。</p>
 *
 * <p>正文在构造时去除首尾空白，长度校验针对去除后的结果，与其它正文命令保持一致。</p>
 */
public record SupplementCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version,

        @NotBlank(message = "补充内容不能为空")
        @Size(max = 10000, message = "补充内容最多10000个字符")
        String content) {

    public SupplementCommand {
        content = content == null ? null : content.strip();
    }
}
