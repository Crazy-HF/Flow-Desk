package com.flowdesk.notification.application.result;

import java.time.LocalDateTime;

/**
 * 站内信列表项。
 *
 * @param id        通知标识
 * @param category  通知类型编码
 * @param title     标题
 * @param body      正文，可为空
 * @param ticketId  关联工单主键，可为空
 * @param ticketNo  工单编号，可为空（前端据此深链到工单详情）
 * @param readAt    已读时间，为空即未读
 * @param createdAt 创建时间（UTC）
 */
public record NotificationResult(
        long id,
        String category,
        String title,
        String body,
        Long ticketId,
        String ticketNo,
        LocalDateTime readAt,
        LocalDateTime createdAt) {
}
