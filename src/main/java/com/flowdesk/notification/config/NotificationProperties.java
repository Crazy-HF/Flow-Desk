package com.flowdesk.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 站内信模块配置（{@code flowdesk.notification.*}）。
 *
 * <p>清理任务两段口径各有一个阈值：{@code retention} 管「已读通知」——删除 {@code read_at}
 * 早于该阈值的行；{@code unreadRetention} 管「未读通知」——删除 {@code created_at} 早于该阈值的行
 * （2026-10-16 裁决，取代原「未读永不清理」；V10 已发布故注释不回改，口径变更记在 V11）。
 * 缺失时都取默认值，与 {@code TicketProperties} 的默认值策略一致。</p>
 *
 * <p><b>与提醒提前量的已知关系</b>：删除会释放 {@code uk_notification_recipient_dedup} 上的键位，
 * 如果一条提醒被删除时它的期限还没到，下一轮扫描会补发一条同 key 的提醒。提前量不超过本阈值时
 * 不会发生——提醒创建于期限前 lead、删除于创建后 unreadRetention，只要 lead ≤ unreadRetention，
 * 删除时刻就落在期限之后，扫描条件（{@code deadline > now}）已经不再成立。</p>
 */
@ConfigurationProperties(prefix = "flowdesk.notification")
public record NotificationProperties(Duration retention, Duration unreadRetention) {

    /** 已读通知保留期默认值：90 天。 */
    private static final Duration DEFAULT_RETENTION = Duration.ofDays(90);
    private static final Duration MIN_RETENTION = Duration.ofHours(1);

    /** 未读通知保留期默认值：3 天（2026-10-16 裁决：未读创建满 3 天即清理）。 */
    private static final Duration DEFAULT_UNREAD_RETENTION = Duration.ofDays(3);
    private static final Duration MIN_UNREAD_RETENTION = Duration.ofMinutes(1);

    public NotificationProperties {
        if (retention == null) {
            retention = DEFAULT_RETENTION;
        }
        if (retention.compareTo(MIN_RETENTION) < 0) {
            throw new IllegalStateException(
                    "flowdesk.notification.retention 必须是不小于 1h 的时长，例如 90d");
        }

        if (unreadRetention == null) {
            unreadRetention = DEFAULT_UNREAD_RETENTION;
        }
        if (unreadRetention.compareTo(MIN_UNREAD_RETENTION) < 0) {
            throw new IllegalStateException(
                    "flowdesk.notification.unread-retention 必须是不小于 1m 的时长，例如 3d");
        }
    }
}
