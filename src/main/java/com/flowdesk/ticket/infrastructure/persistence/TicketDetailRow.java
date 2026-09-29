package com.flowdesk.ticket.infrastructure.persistence;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 复用列表投影，增加正文、终态字段与仅供内部定位使用的 ID。 */
@Getter
@Setter
public class TicketDetailRow extends TicketListRow {
    private Long id;
    private String description;
    private String completionMethod;
    private String closeMethod;
    private String closeReason;
    private LocalDateTime endedAt;
}
