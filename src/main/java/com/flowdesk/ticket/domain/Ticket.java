package com.flowdesk.ticket.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ticket")
public class Ticket {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String ticketNo;
    private String submissionKey;
    private Long requesterId;
    private String title;
    private String description;
    private Long categoryId;
    private String priority;
    private String status;
    private Long assigneeId;
    private LocalDateTime actionDeadlineAt;
    private LocalDateTime cancelRequestedAt;
    private String cancelRequestReason;
    private LocalDateTime cancelRequestDeadlineAt;
    private String completionMethod;
    private String closeMethod;
    private String closeReason;
    private LocalDateTime endedAt;
    private Integer recordSeq;
    private Long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}