package com.flowdesk.ticket.application.query;

import com.flowdesk.common.web.PageQuery;
import jakarta.validation.constraints.AssertTrue;

/** AI 建议历史的分页条件；排序固定由服务和 Mapper 控制。 */
public class TicketAiSuggestionQuery extends PageQuery {

    /** 将历史查询的默认排序方向设为倒序。 */
    public TicketAiSuggestionQuery() {
        setOrderDirection("desc");
    }

    /** 拒绝客户端指定排序字段或改为升序，保持历史分页顺序固定。 */
    @AssertTrue(message = "建议历史固定按创建时间倒序排列")
    public boolean isFixedOrderOnly() {
        return (getOrderBy() == null || getOrderBy().isBlank())
                && "desc".equalsIgnoreCase(getOrderDirection());
    }
}
