package com.flowdesk.ticket.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/** AI 生成请求的持久层元数据行；不保存工单正文或模型输出。 */
@Data
public class TicketAiRequestRow {
    private Long id;
    private Long ticketId;
    private Long userId;
    private String requestId;
    private Long ticketVersion;
    private String inputSha256;
    private String sourceDigest;
    private String status;
    private String failureCode;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}
