package com.flowdesk.ticket.application.service;

import com.flowdesk.ticket.application.command.CreateTicketCommand;
import com.flowdesk.ticket.application.command.ClaimTicketCommand;
import com.flowdesk.ticket.application.command.AddProcessingRecordCommand;
import com.flowdesk.ticket.application.command.SubmitResolutionCommand;
import com.flowdesk.ticket.application.command.ConfirmResolutionCommand;
import com.flowdesk.ticket.application.command.WithdrawSupplementRequestCommand;
import com.flowdesk.ticket.application.command.ReportUnresolvedCommand;
import com.flowdesk.ticket.application.result.TicketActionResult;
import com.flowdesk.ticket.application.result.TicketCreatedResult;

public interface TicketService {

    /** 创建工单 */
    TicketCreatedResult create(CreateTicketCommand command);

    /** 领取工单。 */
    TicketActionResult claim(String ticketNo, ClaimTicketCommand command);

    /** 当前负责人追加处理记录；状态与负责人不变。 */
    TicketActionResult addProcessingRecord(
            String ticketNo, AddProcessingRecordCommand command);

    /** 提交处理结果。 */
    TicketActionResult submitResolution(String ticketNo, SubmitResolutionCommand command);

    /** 确认处理结果。 */
    TicketActionResult confirmResolution(String ticketNo, ConfirmResolutionCommand command);

    /** 当前负责人撤回补充请求；回到处理中并让原补充期限失效。 */
    TicketActionResult withdrawSupplementRequest(
            String ticketNo, WithdrawSupplementRequestCommand command);

    /** 提交人反馈问题未解决；回到处理中并让原确认期限失效，负责人保留。 */
    TicketActionResult reportUnresolved(
            String ticketNo, ReportUnresolvedCommand command);
}
