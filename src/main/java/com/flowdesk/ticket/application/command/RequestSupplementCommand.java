package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 当前负责人请求员工补充信息。
 *
 * <p>工单从「处理中」进入「待补充」，负责人保持不变；补充期限由服务端按
 * {@code flowdesk.ticket.supplement-window} 计算，不接受客户端传入（`docs/kickoff.md` 4.11）。</p>
 *
 * <p>正文必须明确写出需要员工补充什么，上限 10000 与其它正文类动作一致
 * （`docs/api-design.md` 6.3）。正文在构造时去除首尾空白，长度校验针对去除后的结果。</p>
 */
public record RequestSupplementCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version,

        @Size(max = 10000, message = "需要补充的内容最多10000个字符")
        String content) {

    public RequestSupplementCommand {
        content = content == null ? null : content.strip();
    }
}
