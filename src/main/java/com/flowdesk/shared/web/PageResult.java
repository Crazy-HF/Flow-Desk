package com.flowdesk.shared.web;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** 从 1 开始计页的稳定分页结果。 */
public record PageResult<T>(List<T> items, int page, int size, long totalElements, int totalPages) {

    public PageResult {
        if (page < 1 || size < 1 || totalElements < 0 || totalPages < 0) {
            throw new IllegalArgumentException("分页参数必须为有效的非负值，且页码和每页大小从 1 开始");
        }
        items = List.copyOf(Objects.requireNonNull(items, "分页数据不能为 null"));
    }

    /** 将 MyBatis-Plus 分页结果转换为不依赖持久层实体的接口分页结果。 */
    public static <S, T> PageResult<T> from(
            IPage<S> source, Function<? super S, T> converter) {
        Objects.requireNonNull(source, "MyBatis-Plus 分页结果不能为 null");
        Objects.requireNonNull(converter, "分页数据转换器不能为 null");

        List<T> items = source.getRecords().stream()
                .map(converter)
                .toList();

        return new PageResult<>(items,
                Math.toIntExact(source.getCurrent()),
                Math.toIntExact(source.getSize()),
                source.getTotal(),
                Math.toIntExact(source.getPages()));
    }
}
