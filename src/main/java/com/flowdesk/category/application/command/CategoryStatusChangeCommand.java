package com.flowdesk.category.application.command;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 启用或停用分类（{@code docs/api-design.md} 8.4 启用分类 / 停用分类）。
 *
 * <p>只需要目标版本：要改成什么状态由调用的是哪个端点决定，不由请求体决定。</p>
 */
public record CategoryStatusChangeCommand(

        @NotNull
        @PositiveOrZero
        Long version) {
}
