// WithdrawCancelRequestCommand.java
package com.flowdesk.ticket.application.command;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 提交人撤回自己的撤销请求：工单保留原状态，请求失效。
 *
 * <p>只携带版本：撤回是"我改主意了"，与批准一样不需要解释；
 * 形参保持与其它动作一致，便于前端共用同一套提交逻辑。</p>
 */
public record WithdrawCancelRequestCommand(
        @NotNull(message = "版本不能为空")
        @PositiveOrZero(message = "版本不能为负数")
        Long version) {
}