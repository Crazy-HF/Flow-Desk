package com.flowdesk.ticket.application.service;

import com.flowdesk.common.web.PageResult;
import com.flowdesk.ticket.application.command.GenerateTicketAiSuggestionCommand;
import com.flowdesk.ticket.application.query.TicketAiSuggestionQuery;
import com.flowdesk.ticket.application.result.TicketAiSuggestionResult;

/** 单张工单 AI 建议的生成与受限历史查询入口。 */
public interface TicketAiSuggestionService {

    /** 为符合条件的当前负责人生成并保存一条 AI 建议。 */
    TicketAiSuggestionResult generate(
            String ticketNo,
            GenerateTicketAiSuggestionCommand command);

    /** 分页读取当前或最后负责人有权查看的建议历史。 */
    PageResult<TicketAiSuggestionResult> history(
            String ticketNo,
            TicketAiSuggestionQuery query);
}
