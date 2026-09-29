package com.flowdesk.ticket.application.service;

import com.flowdesk.common.web.PageResult;
import com.flowdesk.ticket.application.query.TicketQuery;
import com.flowdesk.ticket.application.query.TicketRecordQuery;
import com.flowdesk.ticket.application.result.TicketDetailResult;
import com.flowdesk.ticket.application.result.TicketListItemResult;
import com.flowdesk.ticket.application.result.TicketRecordResult;

public interface TicketQueryService {

    /**分页查询工单列表*/
    PageResult<TicketListItemResult> page(TicketQuery query);

    /** 根据工单编号查询工单详情*/
    TicketDetailResult detail(String ticketNo);

    /** 根据工单编号查询工单时间线 */
    PageResult<TicketRecordResult> records(String ticketNo, TicketRecordQuery query);
}
