package com.flowdesk.ticket.application.service;

import com.flowdesk.ticket.application.command.CreateTicketCommand;
import com.flowdesk.ticket.application.result.TicketCreatedResult;

public interface TicketService {

    /** 创建工单 */
    TicketCreatedResult create(CreateTicketCommand command);
}