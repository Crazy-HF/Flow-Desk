package com.flowdesk.ticket.application.query;

import com.flowdesk.common.web.PageQuery;
import jakarta.validation.constraints.AssertTrue;

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

    @AssertTrue(message = "时间线固定按记录序号升序排列")
    public boolean isFixedOrderOnly() {
        return getOrderBy() == null && "asc".equals(getOrderDirection());
    }
}
