package com.flowdesk.notification.application.command;

import com.flowdesk.notification.domain.NotificationCategory;

/**
 * 发布一条站内信的入参。
 *
 * <p>由业务模块（工单）在自身的写事务内构造并发布：通知与业务动作同事务写入，
 * 动作回滚时通知一并回滚，不留孤儿。</p>
 *
 * @param recipientId 收件人（工单提交人或当前负责人）
 * @param category    通知类型
 * @param title       标题
 * @param body        正文，可为空
 * @param ticketId    关联工单主键，可为空
 * @param ticketNo    工单编号，可为空
 * @param dedupKey    提醒去重键（同一工单 + 同一期限）；普通业务通知传 {@code null}
 */
public record PublishNotificationCommand(
        long recipientId,
        NotificationCategory category,
        String title,
        String body,
        Long ticketId,
        String ticketNo,
        String dedupKey) {
}
