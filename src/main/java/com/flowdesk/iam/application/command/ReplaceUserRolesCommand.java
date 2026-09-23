package com.flowdesk.iam.application.command;

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
 */
public record ReplaceUserRolesCommand(

        @NotNull
        @PositiveOrZero
        Long version,

        @Size(max = 100)
        List<@NotNull @Positive Long> roleIds
) {
}