package com.flowdesk.iam.application.command;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 账号状态变更请求体（启用与停用共用，{@code docs/api-design.md} 8.2）。
 *
 * <p>启用只读 {@code version}，忽略另外两个字段。停用且目标用户仍负责活动工单时
 * （{@code docs/api-design.md} 8.3），必须同时给出：</p>
 *
 * <ul>
 *   <li>{@code handoffReason}：写进每张受影响工单的 {@code ADMIN_HANDOFF} 记录，长度 1～1000；</li>
 *   <li>{@code handoffs}：目标用户<strong>全部</strong>活动工单的交接方案，缺一张、多一张、
 *       重复一张或版本不符都不接受（{@code 409/ADMIN_HANDOFF_REQUIRED} /
 *       {@code 409/ADMIN_HANDOFF_CONFLICT}）。</li>
 * </ul>
 *
 * <p>目标用户没有活动工单时不要求这两项：没有依据的交接原因和空中楼阁的交接方案都不该写入。</p>
 */
public record UserStatusChangeCommand(

        @NotNull
        @PositiveOrZero
        Long version,

        @Size(max = 1000, message = "交接原因最长 1000 个字符")
        String handoffReason,

        @Valid
        List<AdminHandoffInstruction> handoffs
) {

    /**
     * 不带交接方案的调用：启用账号，以及目标用户当前没有活动工单时的停用。
     *
     * <p>保留这个构造器是为了让既有调用方（前端、既有用例）继续按 {@code {version}} 提交；
     * 一旦目标用户还有活动工单，服务端会用 {@code 409/ADMIN_HANDOFF_REQUIRED} 要求补全方案。</p>
     */
    public UserStatusChangeCommand(Long version) {
        this(version, null, null);
    }
}
