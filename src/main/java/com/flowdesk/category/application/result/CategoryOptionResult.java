package com.flowdesk.category.application.result;

/**
 * 分类选项，只暴露创建工单所需字段。
 */
public record CategoryOptionResult(
        Long id,
        String name
) {
}