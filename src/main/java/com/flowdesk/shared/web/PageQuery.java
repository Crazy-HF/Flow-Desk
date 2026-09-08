package com.flowdesk.shared.web;


import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 通用分页查询请求参数
 * <p>
 * 前端传参示例（GET 请求）：
 * ?pageNo=1&pageSize=20&orderBy=create_time&orderDirection=desc
 * <p>
 * 使用方式：
 * <pre>
 *     PageQuery pageQuery = new PageQuery();
 *     // 控制器中直接绑定参数
 *     public Result pageList(@Valid PageQuery pageQuery) {
 *         Page<Entity> page = pageQuery.toPage();
 *         // 调用 service.page(page, wrapper)
 *     }
 * </pre>
 */
@Data
@NoArgsConstructor
public class PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前页码，默认第 1 页
     */
    @NotNull
    @Min(1)
    private Integer pageNo = 1;

    /**
     * 每页显示条数，默认 20 条，最大 100 条
     */
    @NotNull
    @Min(1)
    @Max(100)
    private Integer pageSize = 20;

    /**
     * 排序字段（数据库字段名），例如：create_time
     */
    private String orderBy;

    /**
     * 排序方向，可选值：asc、desc，默认 asc
     */
    private String orderDirection = "asc";

    /**
     * 转换为 MyBatis-Plus 的 Page 对象。
     * 调用此方法前，应在 Controller 中使用 {@code @Valid} 完成参数校验。
     *
     * @param <T> 实体类型
     * @return MyBatis-Plus 分页对象
     */
    public <T> Page<T> toPage() {
        Page<T> page = new Page<>(pageNo, pageSize);

        // 处理排序
        if (StringUtils.hasText(orderBy)) {
            // 支持多个排序字段，用逗号分隔（可选扩展）
            String[] fields = orderBy.split(",");
            boolean isAsc = !"desc".equalsIgnoreCase(orderDirection);
            List<OrderItem> orderItems = new ArrayList<>();
            for (String field : fields) {
                field = field.trim();
                if (StringUtils.hasText(field)) {
                    orderItems.add(isAsc ? OrderItem.asc(field) : OrderItem.desc(field));
                }
            }
            if (!orderItems.isEmpty()) {
                page.addOrder(orderItems);
            }
        }

        return page;
    }

    /**
     * 快速获取当前页码
     */
    public int getCurrent() {
        return pageNo;
    }

    /**
     * 快速获取每页条数
     */
    public int getSize() {
        return pageSize;
    }

    /**
     * 获取排序规则列表
     */
    public List<OrderItem> getOrderItems() {
        if (!StringUtils.hasText(orderBy)) {
            return Collections.emptyList();
        }
        boolean isAsc = !"desc".equalsIgnoreCase(orderDirection);
        return Arrays.stream(orderBy.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(field -> isAsc ? OrderItem.asc(field) : OrderItem.desc(field))
                .collect(Collectors.toList());
    }
}
