package com.flowdesk.ticket.infrastructure;

import com.flowdesk.attachment.application.port.AttachmentAccessPort;
import com.flowdesk.ticket.application.port.TicketReadPermissionPort;
import com.flowdesk.ticket.mapper.TicketAttachmentMapper;
import com.flowdesk.ticket.mapper.TicketMapper;
import org.springframework.stereotype.Component;

/**
 * 附件业务出口实现：可见性（附件绑定的工单对调用者可见即可读，复用详情同一条可见性 SQL）
 * 与引用判定（是否有工单绑定，供孤儿对账使用）（2026-10-13 第 5、7 步接入）。
 *
 * <p>未绑定或绑定工单不可见一律返回 {@code false}——附件模块据此统一返回 404，
 * 不泄露附件是否存在。</p>
 */
@Component
public class TicketAttachmentAccessAdapter implements AttachmentAccessPort {

    private final TicketAttachmentMapper ticketAttachmentMapper;
    private final TicketMapper ticketMapper;
    private final TicketReadPermissionPort ticketReadPermissionPort;

    public TicketAttachmentAccessAdapter(TicketAttachmentMapper ticketAttachmentMapper,
            TicketMapper ticketMapper,
            TicketReadPermissionPort ticketReadPermissionPort) {
        this.ticketAttachmentMapper = ticketAttachmentMapper;
        this.ticketMapper = ticketMapper;
        this.ticketReadPermissionPort = ticketReadPermissionPort;
    }

    @Override
    public boolean isVisibleTo(long attachmentId, long userId) {
        Long ticketId = ticketAttachmentMapper.selectBoundTicketId(attachmentId);
        if (ticketId == null) {
            return false;
        }
        return ticketMapper.existsVisibleTicketById(
                ticketId,
                userId,
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_OWN"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_QUEUE"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_PARTICIPATED"));
    }

    @Override
    public boolean isReferenced(long attachmentId) {
        return ticketAttachmentMapper.existsBinding(attachmentId);
    }
}