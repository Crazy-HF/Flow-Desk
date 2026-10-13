package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 提交人补充信息。
 *
 * <p>工单从「待补充」回到「处理中」，原补充期限立即失效，原负责人继续处理
 * （`docs/kickoff.md` 4.11）。</p>
 *
 * <p><b>这是 multipart 请求里的 JSON 载体</b>：接口按 `docs/api-design.md` 6.4 使用
 * {@code multipart/form-data}，{@code ticket} 部分携带本类型，新文件走重复 part 名 {@code files}，
 * 已上传的附件通过 {@code attachmentIds} 引用（先传后绑）。<b>正文和附件至少存在一项</b>——
 * 这是跨字段规则，由服务层在暂存之前判定；本类型自身不做正文必填约束。</p>
 *
 * <p>正文在构造时去除首尾空白，长度校验针对去除后的结果，与其它正文命令保持一致。</p>
 */
public record SupplementCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version,

        @Size(max = 10000, message = "补充内容最多10000个字符")
        String content,

        /** 可选：引用当前提交人已上传、尚未绑定的附件（`docs/api-design.md` 6.4/7.1）。 */
        @Size(max = 5, message = "一次最多引用 5 个附件")
        List<@Positive(message = "附件标识必须为正数") Long> attachmentIds) {

    public SupplementCommand {
        content = content == null ? null : content.strip();
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }
}