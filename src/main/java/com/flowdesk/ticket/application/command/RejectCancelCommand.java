// RejectCancelCommand.java
package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 当前负责人拒绝撤销请求：工单保留原状态，撤销请求失效。
 *
 * <p>拒绝原因必填，上限 1000 与其它原因类动作一致（`docs/api-design.md` 6.3）。</p>
 */
public record RejectCancelCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version,

        @NotBlank(message = "拒绝原因不能为空")
        @Size(max = 1000, message = "拒绝原因最长 1000 个字符")
        String reason) {

    public RejectCancelCommand {
        reason = reason == null ? null : reason.strip();
    }
}