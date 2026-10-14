package com.flowdesk.ticket.controller;

import com.flowdesk.common.web.R;
import com.flowdesk.ticket.application.result.TicketAssigneeOptionResult;
import com.flowdesk.ticket.application.service.TicketQueryService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理性交接的候选人查询（{@code docs/api-design.md} 8.3）。
 *
 * <p>与 {@code TicketController#transferCandidates} 分开：那个端点的调用者是工单当前负责人、
 * 要求 {@code TICKET_TRANSFER}、且先过工单可见性；本端点的调用者是系统管理员，只要求
 * {@code TICKET_ADMIN_HANDOFF}，不解锁任何正文可见性。两个权限语义不同，不能合到一个方法上共享注解。</p>
 *
 * <p>返回体是 {@link TicketAssigneeOptionResult}（只有用户 ID 与显示名称）：管理员选人需要的信息
 * 就这些，候选资格（启用 + {@code IT_SUPPORT} + 不是提交人与当前负责人）由服务端重新校验。</p>
 */
@RestController
@RequestMapping("/fd/v1/admin/tickets")
@PreAuthorize("hasAuthority('TICKET_ADMIN_HANDOFF')")
public class AdminTicketHandoffController {

    private final TicketQueryService ticketQueryService;

    public AdminTicketHandoffController(TicketQueryService ticketQueryService) {
        this.ticketQueryService = ticketQueryService;
    }

    /**
     * 查询某张工单的接替候选人。
     *
     * <p>工单不存在返回 {@code 404/TICKET_NOT_FOUND}；工单已不在「有人负责且未终结」的状态时
     * 返回 {@code 409/ADMIN_HANDOFF_CONFLICT} 与当前版本、状态快照；没有候选人时返回空数组。</p>
     */
    @GetMapping("/{ticketNo}/handoff-candidates")
    public R<List<TicketAssigneeOptionResult>> handoffCandidates(
            @PathVariable String ticketNo) {
        return R.success(ticketQueryService.handoffCandidates(ticketNo));
    }
}
