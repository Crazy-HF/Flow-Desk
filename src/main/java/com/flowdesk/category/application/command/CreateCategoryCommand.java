package com.flowdesk.category.application.command;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 新建分类（{@code docs/api-design.md} 8.4 创建分类）。
 *
 * <p>新建的分类一律是启用状态，因此命令里没有 {@code status}：让调用方直接指定状态
 * 等于给出一个"建完就是停用"的入口，而停用是业务动作，应当走启停端点。</p>
 */
public record CreateCategoryCommand(

        @NotBlank
        @Size(max = 100)
        String name,

        @NotNull
        @PositiveOrZero
        Integer sortOrder) {
    public CreateCategoryCommand {
        name = name == null ? null : name.strip();
    }
}
