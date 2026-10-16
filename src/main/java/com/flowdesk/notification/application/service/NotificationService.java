package com.flowdesk.notification.application.service;

import com.flowdesk.common.web.PageResult;
import com.flowdesk.notification.application.command.PublishNotificationCommand;
import com.flowdesk.notification.application.query.NotificationQuery;
import com.flowdesk.notification.application.result.NotificationResult;
import com.flowdesk.notification.application.result.NotificationUnreadCountResult;
import com.flowdesk.notification.domain.NotificationCategory;

import java.util.List;

/** 站内信用例接口：业务侧发布 / 回收 + 本人范围读取。 */
public interface NotificationService {

    /**
     * 发布一条站内信。
     *
     * <p>由工单模块在**同一事务**内调用：通知与业务动作一起提交、一起回滚。</p>
     */
    void publish(PublishNotificationCommand command);

    /**
     * 按工单回收提醒：删除该工单下、属于给定类别的提醒。
     *
     * @param categories 本次作废的提醒类别；空集合等价于什么都不做
     */
    void recallReminders(long ticketId, List<NotificationCategory> categories);

    /** 本人通知列表（可选「只看未读」），按 id 倒序分页。 */
    PageResult<NotificationResult> page(NotificationQuery query);

    /** 本人未读条数。 */
    NotificationUnreadCountResult unreadCount();

    /** 标记本人某条通知已读；非本人或不存在抛 404，已读重复调用幂等。 */
    void markRead(long notificationId);

    /** 标记本人全部未读通知已读；没有未读时幂等。 */
    void markAllRead();
}
