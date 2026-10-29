package com.flowdesk.ticket.infrastructure.persistence;

import lombok.Data;

/** 已成功建议的来源快照；读取时仍须按 sourceTicketId 复核当前可见性。 */
@Data
public class TicketAiSuggestionSourceRow {
    private Long suggestionId;
    private Integer sequenceNo;
    private Long sourceTicketId;
    private String ticketNo;
    private String title;
    private String processExcerpt;
}
