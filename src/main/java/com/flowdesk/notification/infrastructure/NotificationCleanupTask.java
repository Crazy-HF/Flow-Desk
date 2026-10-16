package com.flowdesk.notification.infrastructure;

import com.flowdesk.notification.config.NotificationProperties;
import com.flowdesk.notification.mapper.NotificationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

/**
 * 站内信清理任务（无 HTTP 入口）：删除「已读时间早于保留期」的通知。
 *
 * <p>未读通知永不清理——用户可能长期未登录，删掉未读等于让消息静默消失。
 * 每批上限 {@link #BATCH_SIZE} 行并循环，避免单条 DELETE 在堆积时长时间持锁。</p>
 */
@Component
public class NotificationCleanupTask {

    /** 每天 03:30 执行一轮；v1 演示规模下成本可忽略。 */
    static final String CRON = "0 30 3 * * *";

    private static final int BATCH_SIZE = 500;

    private static final Logger log = LoggerFactory.getLogger(NotificationCleanupTask.class);

    private final NotificationMapper notificationMapper;
    private final NotificationProperties properties;
    private final Clock clock;

    public NotificationCleanupTask(NotificationMapper notificationMapper,
            NotificationProperties properties,
            Clock clock) {
        this.notificationMapper = notificationMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /** 一轮清理；保留期从应用 UTC 时钟推导，与写入时的 {@code created_at} 同源。 */
    @Scheduled(cron = CRON)
    public void cleanReadNotifications() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS).minus(properties.retention()),
                ZoneOffset.UTC);

        int deleted = 0;
        int affected;
        do {
            affected = notificationMapper.deleteReadBefore(cutoff, BATCH_SIZE);
            deleted += affected;
        } while (affected == BATCH_SIZE);

        if (deleted > 0) {
            log.info("站内信清理：删除超过保留期的已读通知 {} 条", deleted);
        }
    }
}
