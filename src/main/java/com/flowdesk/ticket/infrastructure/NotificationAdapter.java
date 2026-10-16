package com.flowdesk.ticket.infrastructure;

import com.flowdesk.notification.application.command.PublishNotificationCommand;
import com.flowdesk.notification.application.service.NotificationService;
import com.flowdesk.notification.domain.NotificationCategory;
import com.flowdesk.ticket.application.port.NotificationPort;
import org.springframework.stereotype.Component;

import java.util.List;

/** 站内信发布出口实现：把工单动作的发布请求委托给站内信服务（同事务写入）。 */
@Component
public class NotificationAdapter implements NotificationPort {

    private final NotificationService notificationService;

    public NotificationAdapter(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 发布一条站内信；调用发生在工单动作的写事务内，与动作一起提交或回滚。
     * @param command
     */
    @Override
    public void publish(PublishNotificationCommand command) {
        notificationService.publish(command);
    }

    /**
     * 回收一张工单下已经作废的等待期提醒（确认 / 补充 / 撤销请求）。
     */
    @Override
    public void recallReminders(long ticketId, List<NotificationCategory> categories) {
        notificationService.recallReminders(ticketId, categories);
    }
}
