package com.flowdesk.iam.application.command;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 替换用户角色的请求体（PUT 语义：携带完整角色集合）。
 *
 * <p>{@code roleIds} 是目标用户<strong>最终应当持有</strong>的全部角色：请求里有、库里没有的新增，
 * 库里有、请求里没有的删除，两边都有的保持原审计字段不动。
 * 允许空集合，表示该用户零角色（2026-09-22 起为合法终态）；为 {@code null} 时按空集合处理。</p>
 *
 * <p><strong>交接方案（{@code docs/api-design.md} 8.3）</strong>：只有这次替换把
 * {@code IT_SUPPORT} 从目标用户身上摘掉、且该用户仍负责活动工单时才要求
 * {@code handoffReason} 与完整的 {@code handoffs}。仅仅新增角色、或保留 {@code IT_SUPPORT} 时，
 * 他还能继续处理手上的工单，不需要交接。</p>
 */
public record ReplaceUserRolesCommand(

        @NotNull
        @PositiveOrZero
        Long version,

        @Size(max = 100)
        List<@NotNull @Positive Long> roleIds,

        @Size(max = 1000, message = "交接原因最长 1000 个字符")
        String handoffReason,

        @Valid
        List<AdminHandoffInstruction> handoffs
) {

    /**
     * 不带交接方案的调用：新增角色、保留 {@code IT_SUPPORT}，以及目标用户没有活动工单时的替换。
     *
     * <p>保留这个构造器是为了让既有调用方（前端、既有用例）继续按 {@code {version, roleIds}} 提交。</p>
     */
    public ReplaceUserRolesCommand(Long version, List<Long> roleIds) {
        this(version, roleIds, null, null);
    }
}
