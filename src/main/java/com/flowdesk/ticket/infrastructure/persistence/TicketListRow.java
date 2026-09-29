package com.flowdesk.ticket.infrastructure.persistence;

import java.time.LocalDateTime;
import lombok.Data;

/** 列表 SQL 的扁平投影，不包含正文、提交键、内部工单 ID。 */
@Data
public class TicketListRow {
    private String ticketNo;
    private String title;
    private Long categoryId;
    private String categoryName;
    private String priority;
    private String status;
    private Long requesterId;
    private String requesterDisplayName;
    private Long assigneeId;
    private String assigneeDisplayName;
    private LocalDateTime actionDeadlineAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Long version;
}
