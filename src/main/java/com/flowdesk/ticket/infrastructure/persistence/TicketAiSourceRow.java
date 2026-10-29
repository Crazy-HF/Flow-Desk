package com.flowdesk.ticket.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/** 受限召回的来源投影；工单 ID 和编号仅供服务端授权、快照与引用映射使用。 */
@Data
public class TicketAiSourceRow {
    private Long ticketId;
    private String ticketNo;
    private String title;
    private String processContent;
    private LocalDateTime endedAt;
}
