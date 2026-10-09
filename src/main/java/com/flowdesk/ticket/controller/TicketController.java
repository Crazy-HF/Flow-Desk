package com.flowdesk.ticket.controller;

import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.R;
import com.flowdesk.common.web.PageResult;
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
import com.flowdesk.ticket.application.query.TicketQuery;
import com.flowdesk.ticket.application.query.TicketRecordQuery;
import com.flowdesk.ticket.application.result.*;
import com.flowdesk.ticket.application.service.TicketQueryService;
import com.flowdesk.ticket.application.service.TicketService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/fd/v1/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final TicketQueryService ticketQueryService;

    public TicketController(TicketService ticketService,
            TicketQueryService ticketQueryService) {
        this.ticketService = ticketService;
        this.ticketQueryService = ticketQueryService;
    }

    /** 每个显式查询范围分别校验权限，不能用任意查询权限替代。 */
    @GetMapping
    @PreAuthorize("""
            (#p0.scope?.name() == 'REQUESTED_BY_ME'
                and hasAuthority('TICKET_VIEW_OWN'))
            or (#p0.scope?.name() == 'PENDING_QUEUE'
                and hasAuthority('TICKET_VIEW_QUEUE'))
            or ((#p0.scope?.name() == 'ASSIGNED_TO_ME'
                 or #p0.scope?.name() == 'PARTICIPATED_BY_ME')
                and hasAuthority('TICKET_VIEW_PARTICIPATED'))
            """)
    public R<PageResult<TicketListItemResult>> page(
            @Valid @ModelAttribute TicketQuery query) {
        return R.success(ticketQueryService.page(query));
    }

    /** 可见性由服务同时校验权限与工单关系，无权与不存在统一返回 404。 */
    @GetMapping("/{ticketNo}")
    public R<TicketDetailResult> detail(@PathVariable String ticketNo) {
        return R.success(ticketQueryService.detail(ticketNo));
    }

    /** 先由服务校验工单可见性，再分页读取记录。 */
    @GetMapping("/{ticketNo}/records")
    public R<PageResult<TicketRecordResult>> records(
            @PathVariable String ticketNo,
            @Valid @ModelAttribute TicketRecordQuery query) {
        return R.success(ticketQueryService.records(ticketNo, query));
    }

    /** 候选人 :先由服务校验工单可见性，再返回仍可接收该工单的启用 IT 用户。 */
    @GetMapping("/{ticketNo}/transfer-candidates")
    public R<List<TicketAssigneeOptionResult>> transferCandidates(
            @PathVariable String ticketNo) {
        return R.success(ticketQueryService.transferCandidates(ticketNo));
    }

    /**创建工单*/
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('TICKET_CREATE')")
    public ResponseEntity<R<TicketCreatedResult>> create(
            @Valid @RequestPart("ticket") CreateTicketCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(R.success(ticketService.create(command)));
    }

    /** 领取工单 */
    @PostMapping("/{ticketNo}/actions/claim")
    public R<TicketActionResult> claim(
            @PathVariable String ticketNo,
            @Valid @RequestBody ClaimTicketCommand command) {
        return R.success(ticketService.claim(ticketNo, command));
    }

    /** 当前负责人追加处理记录；状态与负责人不变，只递增版本与记录序号。 */
    @PostMapping("/{ticketNo}/actions/add-processing-record")
    public R<TicketActionResult> addProcessingRecord(
            @PathVariable String ticketNo,
            @Valid @RequestBody AddProcessingRecordCommand command) {
        return R.success(ticketService.addProcessingRecord(ticketNo, command));
    }

    /** 当前负责人提交解决结果；进入待确认并生成确认期限。 */
    @PostMapping("/{ticketNo}/actions/submit-resolution")
    public R<TicketActionResult> submitResolution(
            @PathVariable String ticketNo,
            @Valid @RequestBody SubmitResolutionCommand command) {
        return R.success(ticketService.submitResolution(ticketNo, command));
    }

    /** 提交人确认问题已解决；进入终态 COMPLETED。 */
    @PostMapping("/{ticketNo}/actions/confirm-resolution")
    public R<TicketActionResult> confirmResolution(
            @PathVariable String ticketNo,
            @Valid @RequestBody ConfirmResolutionCommand command) {
        return R.success(ticketService.confirmResolution(ticketNo, command));
    }

    /** 当前负责人撤回补充请求；回到处理中并让补充期限失效。 */
    @PostMapping("/{ticketNo}/actions/withdraw-supplement-request")
    public R<TicketActionResult> withdrawSupplementRequest(
            @PathVariable String ticketNo,
            @Valid @RequestBody WithdrawSupplementRequestCommand command) {
        return R.success(ticketService.withdrawSupplementRequest(ticketNo, command));
    }

    /** 提交人反馈问题未解决；回到处理中并让确认期限失效。 */
    @PostMapping("/{ticketNo}/actions/report-unresolved")
    public R<TicketActionResult> reportUnresolved(
            @PathVariable String ticketNo,
            @Valid @RequestBody ReportUnresolvedCommand command) {
        return R.success(ticketService.reportUnresolved(ticketNo, command));
    }

    /** 当前负责人请求员工补充信息；进入待补充并写入服务端计算的补充期限。 */
    @PostMapping("/{ticketNo}/actions/request-supplement")
    public R<TicketActionResult> requestSupplement(
            @PathVariable String ticketNo,
            @Valid @RequestBody RequestSupplementCommand command) {
        return R.success(ticketService.requestSupplement(ticketNo, command));
    }

    /**提交人补充信息；回到处理中并让补充期限失效。*/
    @PostMapping(value = "/{ticketNo}/actions/supplement",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public R<TicketActionResult> supplement(
            @PathVariable String ticketNo,
            @Valid @RequestPart("ticket") SupplementCommand command,
            @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        if (files != null && !files.isEmpty()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "本版本不支持附件，请只提交补充正文");
        }

        return R.success(ticketService.supplement(ticketNo, command));
    }

    /** 当前负责人调整分类；状态、负责人与期限不变。 */
    @PostMapping("/{ticketNo}/actions/change-category")
    public R<TicketActionResult> changeCategory(
            @PathVariable String ticketNo,
            @Valid @RequestBody ChangeCategoryCommand command) {
        return R.success(ticketService.changeCategory(ticketNo, command));
    }

    /** 当前负责人调整优先级；状态、负责人与期限不变。 */
    @PostMapping("/{ticketNo}/actions/change-priority")
    public R<TicketActionResult> changePriority(
            @PathVariable String ticketNo,
            @Valid @RequestBody ChangePriorityCommand command) {
        return R.success(ticketService.changePriority(ticketNo, command));
    }

    /** 当前负责人直接转交；状态与期限不变，负责人立即替换。 */
    @PostMapping("/{ticketNo}/actions/transfer")
    public R<TicketActionResult> transfer(
            @PathVariable String ticketNo,
            @Valid @RequestBody TransferCommand command) {
        return R.success(ticketService.transfer(ticketNo, command));
    }

    /** 当前负责人异常关闭工单；只有「处理中」可以关闭，进入终态 CLOSED。 */
    @PostMapping("/{ticketNo}/actions/close")
    public R<TicketActionResult> close(
            @PathVariable String ticketNo,
            @Valid @RequestBody CloseTicketCommand command) {
        return R.success(ticketService.close(ticketNo, command));
    }

    /** 提交人发起撤销请求；工单状态不变，等待当前负责人批准或拒绝。 */
    @PostMapping("/{ticketNo}/actions/request-cancel")
    public R<TicketActionResult> requestCancel(
            @PathVariable String ticketNo,
            @Valid @RequestBody RequestCancelCommand command) {
        return R.success(ticketService.requestCancel(ticketNo, command));
    }

    /** 当前负责人批准撤销请求；工单进入终态 CANCELED。 */
    @PostMapping("/{ticketNo}/actions/approve-cancel")
    public R<TicketActionResult> approveCancel(
            @PathVariable String ticketNo,
            @Valid @RequestBody ApproveCancelCommand command) {
        return R.success(ticketService.approveCancel(ticketNo, command));
    }

    /** 当前负责人拒绝撤销请求；工单保留原状态。 */
    @PostMapping("/{ticketNo}/actions/reject-cancel")
    public R<TicketActionResult> rejectCancel(
            @PathVariable String ticketNo,
            @Valid @RequestBody RejectCancelCommand command) {
        return R.success(ticketService.rejectCancel(ticketNo, command));
    }

    /** 提交人撤回自己的撤销请求；工单保留原状态。 */
    @PostMapping("/{ticketNo}/actions/withdraw-cancel-request")
    public R<TicketActionResult> withdrawCancelRequest(
            @PathVariable String ticketNo,
            @Valid @RequestBody WithdrawCancelRequestCommand command) {
        return R.success(ticketService.withdrawCancelRequest(ticketNo, command));
    }

    /** 提交人撤销自己的工单；只剩「待受理」可以这样撤销，进入终态 CANCELED。 */
    @PostMapping("/{ticketNo}/actions/cancel")
    public R<TicketActionResult> cancel(
            @PathVariable String ticketNo,
            @Valid @RequestBody CancelTicketCommand command) {
        return R.success(ticketService.cancel(ticketNo, command));
    }

}
