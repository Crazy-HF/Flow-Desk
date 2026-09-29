package com.flowdesk.category.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 修改分类的名称或排序值（{@code docs/api-design.md} 8.4 修改分类）。
 *
 * <p>没有 {@code status}：启停是独立端点，两者各自只改一件事，审计时才能说清是哪一步做的。
 * {@code version} 是乐观锁依据，与行数据不一致时后端返回 {@code 409/CATEGORY_CONFLICT}。</p>
 */
public record UpdateCategoryCommand(

        @NotBlank
        @Size(max = 100)
        String name,

        @NotNull
        @PositiveOrZero
        Integer sortOrder,

        @NotNull
        @PositiveOrZero
        Long version) {
    public UpdateCategoryCommand {
        name = name == null ? null : name.strip();
    }
}
