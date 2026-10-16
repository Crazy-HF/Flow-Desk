package com.flowdesk.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 站内信模块配置（{@code flowdesk.notification.*}）。
 *
 * <p>{@code retention} 是「已读通知」的保留期：清理任务删除 {@code read_at} 早于该阈值的通知，
 * 未读通知永不清理（用户可能长期未登录）。缺失时取默认值，与 {@code TicketProperties}
 * 的默认值策略一致。</p>
 */
@ConfigurationProperties(prefix = "flowdesk.notification")
public record NotificationProperties(Duration retention) {

    /** 已读通知保留期默认值：90 天。 */
    private static final Duration DEFAULT_RETENTION = Duration.ofDays(90);
    private static final Duration MIN_RETENTION = Duration.ofHours(1);

    public NotificationProperties {
        if (retention == null) {
            retention = DEFAULT_RETENTION;
        }
        if (retention.compareTo(MIN_RETENTION) < 0) {
            throw new IllegalStateException(
                    "flowdesk.notification.retention 必须是不小于 1h 的时长，例如 90d");
        }
    }
}
