package com.flowdesk.category.application.query;

import com.flowdesk.common.web.PageQuery;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * 分类管理列表的查询条件（{@code docs/api-design.md} 8.4）。
 *
 * <p>{@code status} 用 {@code String} 而不是新增枚举：{@code TicketCategory.status} 与
 * {@code ck_ticket_category_status} 都是字符串，本模块已有代码（{@code CategoryServiceImpl}）
 * 也按字符串常量比较，多引一个枚举只会让同一份取值在三处各表述一次。</p>
 */
@Getter
@Setter
public class CategoryQuery extends PageQuery {

    /** 按名称做字面子串搜索；长度为空的字符串视为没有筛选。 */
    @Size(max = 100)
    private String keyword;

    /**
     * 只保留该状态的分类；不传表示启用与停用一起返回。
     *
     * <p>Bean Validation 把 {@code null} 视为合法，因此这里不需要额外的"可空"分支。</p>
     */
    @Pattern(regexp = "ENABLED|DISABLED")
    private String status;
}
