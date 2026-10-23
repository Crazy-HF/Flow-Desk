package com.flowdesk.ticket.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/** 已保存的 AI 建议及实际输入快照的持久层投影。 */
@Data
public class TicketAiSuggestionRow {
    private Long id;
    private Long requestRowId;
    private Long ticketId;
    private String requestId;

    private String inputTitle;
    private String inputDescription;
    private String inputCategory;
    private String inputPriority;
    private String inputStatus;

    private String summary;
    private String questionsJson;
    private String suggestedStepsJson;

    private String model;
    private String promptVersion;
    private Integer inputTokens;
    private Integer outputTokens;
    private LocalDateTime createdAt;
}
