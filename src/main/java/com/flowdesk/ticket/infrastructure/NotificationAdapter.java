package com.flowdesk.ticket.infrastructure;

import com.flowdesk.notification.application.command.PublishNotificationCommand;
import com.flowdesk.notification.application.service.NotificationService;
import com.flowdesk.ticket.application.port.NotificationPort;
import org.springframework.stereotype.Component;

/** 站内信发布出口实现：把工单动作的发布请求委托给站内信服务（同事务写入）。 */
@Component
public class NotificationAdapter implements NotificationPort {

    private final NotificationService notificationService;

    public NotificationAdapter(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void publish(PublishNotificationCommand command) {
        notificationService.publish(command);
    }
}
