package com.flowdesk.ticket.application.query;

import com.flowdesk.common.web.PageQuery;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.util.StringUtils;

/** 时间线仅支持分页，记录顺序固定为 sequenceNo 升序。 */
public class TicketRecordQuery extends PageQuery {
    public Integer getPage() {
        return getPageNo();
    }

    public void setPage(Integer page) {
        setPageNo(page);
    }

    public void setSize(int size) {
        setPageSize(size);
    }

    /** 时间线只支持分页；排序方向沿用 {@link PageQuery} 的大小写无关口径。 */
    @AssertTrue(message = "时间线固定按记录序号升序排列")
    public boolean isFixedOrderOnly() {
        return !StringUtils.hasText(getOrderBy()) && isAscendingDirection();
    }
}
