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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface TicketService {

    /** 创建工单（可携带新附件 files 或引用已上传附件 attachmentIds）。 */
    TicketCreatedResult create(CreateTicketCommand command, List<MultipartFile> files);

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

    /** 提交人补充信息（正文与附件至少一项）；回到处理中并让原补充期限失效，负责人保留。 */
    TicketActionResult supplement(
            String ticketNo, SupplementCommand command, List<MultipartFile> files);

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

    /**
     * 系统自动完成到期未确认的工单
     * 「待确认」且确认期限已过的工单转为「已完成」，完成方式记为 AUTO_CONFIRM_TIMEOUT。
     *
     * @param limit 单轮处置上限；被人工动作抢先的工单不计入
     * @return 本轮实际完成的工单数
     */
    int completeExpiredConfirmations(int limit);

    /**
     * 系统自动关闭到期未补充的工单
     * 「待补充」且补充期限已过的工单转为「已关闭」，关闭方式记为 AUTO_SUPPLEMENT_TIMEOUT。
     */
    int closeExpiredSupplements(int limit);

    /**
     * 系统发布到期前提醒
     * 确认 / 补充期限与撤销请求期限进入提前量窗口时各提醒一次：
     * 确认 / 补充提醒发给提交人，撤销请求提醒发给当前负责人；不改变任何工单状态。
     */
    int publishDueSoonReminders(int limit);

    /**
     * 系统回收「撤销请求已过期」工单上残留的到期前提醒
     * 撤销请求到期不产生业务动作，所以没有业务入口会回收它的到期前提醒：工单只要一直没人动，
     * 那条"还剩 N 小时"就会一直挂在负责人收件箱里，直到站内信清理任务按未读保留期兜底删掉。
     * 这里按「过期不超过一天」的回看窗口把它提前收掉，不改变任何工单状态与记录。
     *
     * @param limit 单轮处理上限；期间已被业务动作回收、或已被重新发起（不再过期）的工单不计入
     * @return 本轮完成回收的工单数（提醒早已不在时是一次空操作，不清点删除行数）
     */
    int recallExpiredCancelReminders(int limit);
}
