package com.flowdesk.shared.api;

import java.util.List;

/** 从 1 开始计页的稳定分页结果。 */
public record PageResult<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public PageResult {
        if (page < 1 || size < 1 || totalElements < 0 || totalPages < 0) {
            throw new IllegalArgumentException("分页参数必须为有效的非负值，且页码和每页大小从 1 开始");
        }
        items = List.copyOf(items);
    }
}
