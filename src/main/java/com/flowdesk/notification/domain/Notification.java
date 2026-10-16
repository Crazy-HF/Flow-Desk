package com.flowdesk.notification.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 站内信（应用内通知），对应 {@code notification} 表。
 *
 * <p>只按收件人本人范围读写；{@code readAt} 为空即未读。</p>
 */
@Data
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long recipientId;
    private String category;
    private String title;
    private String body;
    private Long ticketId;
    private String ticketNo;
    private String dedupKey;
    private LocalDateTime readAt;
    private LocalDateTime createdAt;
}
