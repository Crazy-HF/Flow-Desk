package com.flowdesk.category.application.result;

import java.time.OffsetDateTime;

/**
 * 分类管理接口返回的分类详情（{@code docs/api-design.md} 8.4）。
 *
 * <p>不返回任何工单引用计数：分类是否"仍被工单使用"只在删除时由后端判定，
 * 把它做成列表字段会让界面产生"可以据此判断能否删除"的错觉——引用是随时会变的。</p>
 */
public record CategoryResult(
        Long id,
        String name,
        String status,
        Integer sortOrder,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        Long version
) {
}
