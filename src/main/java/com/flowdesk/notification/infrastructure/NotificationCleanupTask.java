package com.flowdesk.notification.infrastructure;

import com.flowdesk.notification.config.NotificationProperties;
import com.flowdesk.notification.mapper.NotificationMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.function.IntSupplier;

/**
 * 站内信清理任务（无 HTTP 入口）：一轮跑两段口径。
 *
 * <p>① 已读通知：{@code read_at} 早于 {@code flowdesk.notification.retention}（默认 90d）——
 * 看过的消息没有长期留存价值；
 * ② 未读通知：{@code created_at} 早于 {@code flowdesk.notification.unread-retention}（默认 3d）——
 * 收件人长期不看就清掉，否则未读徽标与收件箱会被历史消息永久占住。
 * 2026-10-16 口径变更，取代原「未读永不清理」，见迁移 {@code V11}。</p>
 */
@Component
@Slf4j
public class NotificationCleanupTask {

    /** 每天 6.00 执行一轮；v1 演示规模下成本可忽略。 */
    static final String CRON = "0 0 6 * * *";

    private static final int BATCH_SIZE = 500;

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
    public void cleanNotifications() {
        //1.判定时间只取一次，两个阈值必须来自同一个瞬间，否则跨秒执行边界会漂
        LocalDateTime now = LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);

        //2.第一段 ： 已读时间超过保留期的
        int readDeleted = deleteInBatches(
                ()->notificationMapper.deleteReadBefore(now.minus(properties.retention()),BATCH_SIZE));
        if(readDeleted > 0){
            log.info("站内信清理：删除超过保留期的已读通知 {} 条", readDeleted);
        }

        //3.第二段 ： 未读且创建时间超过未读保留期的
        int unreadDeleted = deleteInBatches(
                ()->notificationMapper.deleteUnreadBefore(now.minus(properties.unreadRetention()),BATCH_SIZE));
        if(unreadDeleted > 0){
            log.info("站内信清理：删除超过未读保留期的未读通知 {} 条", unreadDeleted);
        }
    }

    /**
     * 分批删除直到某一批不足一整批为止，返回累计删除行数。
     *
     * <p>传 {@link IntSupplier} 而不是传「阈值 / 语句」：两段的删除语句与阈值都不同，
     * 但「删到删不动为止」的节奏是同一个，抽出来的只有节奏。</p>
     */
    private int deleteInBatches(IntSupplier batchDelete) {
        int total = 0;
        int affected;
        do{
            affected = batchDelete.getAsInt();
            total += affected;
        } while (affected == BATCH_SIZE);
        return total;
    }
}
