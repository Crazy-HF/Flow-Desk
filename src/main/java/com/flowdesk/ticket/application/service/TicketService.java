package com.flowdesk.ticket.application.service;

import com.flowdesk.ticket.application.command.AddProcessingRecordCommand;
import com.flowdesk.ticket.application.command.ApproveCancelCommand;
import com.flowdesk.ticket.application.command.CancelTicketCommand;
import com.flowdesk.ticket.application.command.ChangeCategoryCommand;
import com.flowdesk.ticket.application.command.ChangePriorityCommand;
import com.flowdesk.ticket.application.command.ClaimTicketCommand;
import com.flowdesk.ticket.application.command.CloseTicketCommand;
import com.flowdesk.ticket.application.command.ConfirmResolutionCommand;
import com.flowdesk.ticket.application.command.CreateTicketCommand;
import com.flowdesk.ticket.application.command.RejectCancelCommand;
import com.flowdesk.ticket.application.command.ReportUnresolvedCommand;
import com.flowdesk.ticket.application.command.RequestCancelCommand;
import com.flowdesk.ticket.application.command.RequestSupplementCommand;
import com.flowdesk.ticket.application.command.SubmitResolutionCommand;
import com.flowdesk.ticket.application.command.SupplementCommand;
import com.flowdesk.ticket.application.command.TransferCommand;
import com.flowdesk.ticket.application.command.WithdrawCancelRequestCommand;
import com.flowdesk.ticket.application.command.WithdrawSupplementRequestCommand;
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

    /** 当前负责人请求员工补充信息；进入待补充并写入服务端计算的补充期限。 */
    TicketActionResult requestSupplement(
            String ticketNo, RequestSupplementCommand command);

    /** 提交人补充信息；回到处理中并让原补充期限失效，负责人保留。 */
    TicketActionResult supplement(
            String ticketNo, SupplementCommand command);

    /** 当前负责人调整工单分类；状态、负责人与期限都不变。 */
    TicketActionResult changeCategory(
            String ticketNo, ChangeCategoryCommand command);

    /** 当前负责人调整工单优先级；状态、负责人与期限都不变。 */
    TicketActionResult changePriority(
            String ticketNo, ChangePriorityCommand command);

    /** 当前负责人直接转交给另一名 IT 支持人员；状态与期限不变，负责人立即替换。 */
    TicketActionResult transfer(
            String ticketNo, TransferCommand command);

    /** 当前负责人手动关闭工单；只有「处理中」可以关闭，进入终态「已关闭」。 */
    TicketActionResult close(
            String ticketNo, CloseTicketCommand command);

    /** 提交人发起撤销请求；工单状态不变，请求进入待批准。 */
    TicketActionResult requestCancel(
            String ticketNo, RequestCancelCommand command);

    /** 当前负责人批准撤销请求；进入终态「已取消」。 */
    TicketActionResult approveCancel(
            String ticketNo, ApproveCancelCommand command);

    /** 当前负责人拒绝撤销请求；状态与期限不变，请求失效。 */
    TicketActionResult rejectCancel(
            String ticketNo, RejectCancelCommand command);

    /** 提交人撤回自己的撤销请求；状态与期限不变，请求失效。 */
    TicketActionResult withdrawCancelRequest(
            String ticketNo, WithdrawCancelRequestCommand command);

    /** 提交人撤销自己的工单；只剩「待受理」可以这样撤销，其余非终态走两阶段 */
    TicketActionResult cancel(
            String ticketNo, CancelTicketCommand command);
}
