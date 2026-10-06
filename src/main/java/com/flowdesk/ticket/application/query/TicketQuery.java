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
}
