package com.flowdesk.common.web;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.flowdesk.common.exception.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 通用分页查询请求参数。
 *
 * <p>GET 传参示例：{@code ?pageNo=1&pageSize=20&orderBy=created_at&orderDirection=desc}</p>
 *
 * <p>排序字段必须由调用方提供固定白名单，未列入白名单的字段会被拒绝，
 * 避免把客户端输入原样拼进 SQL 的 ORDER BY。</p>
 */
@Data
@NoArgsConstructor
public class PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 当前页码，从 1 开始。 */
    @NotNull
    @Min(1)
    private Integer pageNo = 1;

    /** 每页条数，最大 100。 */
    @NotNull
    @Min(1)
    @Max(100)
    private Integer pageSize = 20;

    /** 排序字段，必须落在调用方提供的白名单内；逗号分隔表示多字段。 */
    private String orderBy;

    /** 排序方向，只接受 {@code asc} 或 {@code desc}。 */
    private String orderDirection = "asc";

    /** 快速获取当前页码。 */
    public int getCurrent() {
        return pageNo;
    }

    /** 快速获取每页条数。 */
    public int getSize() {
        return pageSize;
    }

    /**
     * 按白名单解析排序字段。
     *
     * @param allowedOrderFields 允许排序的数据库字段名
     * @return 排序项；未请求排序时返回空列表，由调用方决定默认排序
     * @throws ApiException 排序字段不在白名单内或排序方向非法时抛出 400/VALIDATION_FAILED
     */
    public List<OrderItem> orderItems(Set<String> allowedOrderFields) {
        if (!StringUtils.hasText(orderBy)) {
            return List.of();
        }
        boolean ascending = isAscending();
        List<OrderItem> orderItems = new ArrayList<>();
        for (String rawField : orderBy.split(",")) {
            String field = rawField.trim();
            if (field.isEmpty()) {
                continue;
            }
            if (!allowedOrderFields.contains(field)) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "VALIDATION_FAILED",
                        "排序字段不在允许范围内");
            }
            orderItems.add(ascending ? OrderItem.asc(field) : OrderItem.desc(field));
        }
        return orderItems;
    }

    private boolean isAscending() {
        if (StringUtils.hasText(orderDirection) && "desc".equalsIgnoreCase(orderDirection)) {
            return false;
        }
        if (!StringUtils.hasText(orderDirection) || "asc".equalsIgnoreCase(orderDirection)) {
            return true;
        }
        throw new ApiException(HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "排序方向只能是 asc 或 desc");
    }
}
