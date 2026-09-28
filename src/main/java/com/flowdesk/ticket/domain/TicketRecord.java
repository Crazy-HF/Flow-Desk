package com.flowdesk.ticket.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("ticket_record")
public class TicketRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long ticketId;
    private Integer sequenceNo;
    private String recordType;
    private String actorType;
    private Long actorUserId;
    private String content;
    private String reason;
    private String fromStatus;
    private String toStatus;
    private Long fromAssigneeId;
    private Long toAssigneeId;
    private Long fromCategoryId;
    private Long toCategoryId;
    private String fromPriority;
    private String toPriority;
    private LocalDateTime deadlineAt;
    private String completionMethod;
    private String closeMethod;
    private String closeReason;
    private LocalDateTime createdAt;
}