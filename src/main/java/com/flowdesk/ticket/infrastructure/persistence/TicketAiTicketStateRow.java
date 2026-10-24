package com.flowdesk.ticket.infrastructure.persistence;

import lombok.Data;

/** 两段短事务中锁定并复核工单状态、负责人和版本的最小投影。 */
@Data
public class TicketAiTicketStateRow {
    private Long id;
    private Long version;
    private String status;
    private Long assigneeId;
}
