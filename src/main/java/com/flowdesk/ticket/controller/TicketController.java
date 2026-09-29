package com.flowdesk.ticket.controller;

import com.flowdesk.common.web.R;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.ticket.application.query.TicketQuery;
import com.flowdesk.ticket.application.query.TicketRecordQuery;
import com.flowdesk.ticket.application.result.TicketRecordResult;
import com.flowdesk.ticket.application.result.TicketListItemResult;
import com.flowdesk.ticket.application.result.TicketDetailResult;
import com.flowdesk.ticket.application.service.TicketQueryService;
import com.flowdesk.ticket.application.command.CreateTicketCommand;
import com.flowdesk.ticket.application.result.TicketCreatedResult;
import com.flowdesk.ticket.application.service.TicketService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;

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

    /**创建工单*/
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('TICKET_CREATE')")
    public ResponseEntity<R<TicketCreatedResult>> create(
            @Valid @RequestPart("ticket") CreateTicketCommand command) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(R.success(ticketService.create(command)));
    }
}
