package com.flowdesk.ticket.application.query;

import com.flowdesk.common.web.PageQuery;
import com.flowdesk.ticket.domain.TicketListSort;
import com.flowdesk.ticket.domain.TicketPriority;
import com.flowdesk.ticket.domain.TicketScope;
import com.flowdesk.ticket.domain.TicketStatus;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.util.StringUtils;

@Getter
@Setter
public class TicketQuery extends PageQuery {

    @NotNull(message = "查询范围不能为空")
    private TicketScope scope;

    private List<@NotNull TicketStatus> status;
    private List<@NotNull TicketPriority> priority;

    @Positive
    private Long categoryId;

    @Size(max = 200, message = "关键词最多200个字符")
    private String keyword;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime createdFrom;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime createdTo;

    private TicketListSort sort;

    /**
     * IT 列表的「待我批准」筛选（{@code docs/api-design.md} 5.3，完整版 backlog 第 7 项）。
     *
     * <p>语义固定为「本人是当前负责人 且 工单上有**未过期**的待决撤销请求」，与详情里的
     * {@code approve-cancel} / {@code reject-cancel} 两格**共用同一判定**（其上再叠加
     * {@code TICKET_PROCESS} 与「待补充期间不裁决」两条既有条件）。列表项响应不因此增加字段：
     * 这个参数只决定命中哪些工单，待决请求本身仍只在详情返回。</p>
     *
     * <p>只在 {@code scope=ASSIGNED_TO_ME} 下有定义——其它范围传 {@code true} 是参数用错，
     * 由下面的跨字段校验按 {@code 400} 拒绝，而不是静默忽略（静默忽略会让调用方以为筛选生效了）。</p>
     */
    private Boolean awaitingMyApproval;

    /** 未指定排序时，队列按优先级/创建时间，其他范围按更新时间。 */
    public TicketListSort getSort() {
        if (sort != null) {
            return sort;
        }
        return scope == TicketScope.PENDING_QUEUE
                ? TicketListSort.PRIORITY_DESC_CREATED_ASC
                : TicketListSort.UPDATED_DESC;
    }

    /** 工单 API 的 page/size 映射到现有分页字段，复用父类校验。 */
    public Integer getPage() {
        return getPageNo();
    }

    public void setPage(Integer page) {
        setPageNo(page);
    }

    public void setSize(int size) {
        setPageSize(size);
    }

    @AssertTrue(message = "创建时间起点不能晚于终点")
    public boolean isCreatedRangeValid() {
        return createdFrom == null || createdTo == null
                || !createdFrom.toInstant().isAfter(createdTo.toInstant());
    }

    /** 工单列表只支持固定 {@code sort} 编码；排序方向沿用 {@link PageQuery} 的大小写无关口径。 */
    @AssertTrue(message = "工单排序请使用固定 sort 编码")
    public boolean isFixedSortOnly() {
        return !StringUtils.hasText(getOrderBy()) && isAscendingDirection();
    }

    /**
     * 「待我批准」只在「我负责的」范围里有定义：其它范围传 {@code true} 属于参数用错。
     *
     * <p>与 {@code createdFrom} / {@code createdTo} 的跨字段校验同一风格——注解表达不了的关系写在
     * {@code @AssertTrue} 里，失败即 {@code 400/VALIDATION_FAILED}。</p>
     */
    @AssertTrue(message = "待我批准筛选只能用于 ASSIGNED_TO_ME 范围")
    public boolean isAwaitingMyApprovalScopeValid() {
        return !Boolean.TRUE.equals(awaitingMyApproval)
                || scope == TicketScope.ASSIGNED_TO_ME;
    }
}
