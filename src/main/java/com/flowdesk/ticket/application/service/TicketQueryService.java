package com.flowdesk.ticket.application.service;

import com.flowdesk.common.web.PageResult;
import com.flowdesk.ticket.application.query.TicketQuery;
import com.flowdesk.ticket.application.query.TicketRecordQuery;
import com.flowdesk.ticket.application.result.TicketAssigneeOptionResult;
import com.flowdesk.ticket.application.result.TicketDetailResult;
import com.flowdesk.ticket.application.result.TicketListItemResult;
import com.flowdesk.ticket.application.result.TicketRecordResult;

import java.util.List;

public interface TicketQueryService {

    /**分页查询工单列表*/
    PageResult<TicketListItemResult> page(TicketQuery query);

    /** 根据工单编号查询工单详情*/
    TicketDetailResult detail(String ticketNo);

    /** 根据工单编号查询工单时间线 */
    PageResult<TicketRecordResult> records(String ticketNo, TicketRecordQuery query);

    /**查询转交候选人：仍可接收该工单的启用 IT 用户，排除提交人与当前负责人。*/
    List<TicketAssigneeOptionResult> transferCandidates(String ticketNo);
}
