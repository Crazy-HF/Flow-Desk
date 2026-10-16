package com.flowdesk.ticket.application.port;

import com.flowdesk.notification.application.command.PublishNotificationCommand;

/**
 * 站内信发布出口：工单模块声明「需要发布一条通知」，由工单模块自己的
 * {@code infrastructure} 适配器委托站内信服务实现。
 *
 * <p>方向与 {@code CategoryAvailabilityPort} 一致（消费者声明出口、消费者实现适配器），
 * 站内信模块不反向依赖工单模块。</p>
 */
public interface NotificationPort {

    /** 发布一条站内信；调用发生在工单动作的写事务内，与动作一起提交或回滚。 */
    void publish(PublishNotificationCommand command);
}
