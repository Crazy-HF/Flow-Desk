package com.flowdesk.ticket.application.service;

import com.flowdesk.common.web.PageResult;
import com.flowdesk.ticket.application.query.TicketQuery;
import com.flowdesk.ticket.application.result.TicketListItemResult;

public interface TicketQueryService {
    PageResult<TicketListItemResult> page(TicketQuery query);
}
