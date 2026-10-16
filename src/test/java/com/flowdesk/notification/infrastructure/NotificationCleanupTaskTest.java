package com.flowdesk.notification.infrastructure;

import com.flowdesk.notification.config.NotificationProperties;
import com.flowdesk.notification.mapper.NotificationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站内信清理任务的批处理协议：删除阈值只来自应用 UTC 时钟减保留期，未读永不命中，
 * 每批 500 行并循环到某批不满为止。
 *
 * <p>被测类直接 {@code new} 出来；它守的是"清理的边界值从同一个时钟推导、循环不能提前停"——
 * 提前停会让堆积的通知永远删不完，而多删一行就是删掉用户的未读消息。</p>
 */
@ExtendWith(MockitoExtension.class)
// 循环用例共享"恰好满批"的连续桩，未使用的桩不应判定为失败。
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationCleanupTaskTest {

    /** 故意带纳秒，用于断言阈值按毫秒截断。 */
    private static final Instant CLOCK_INSTANT = Instant.parse("2026-10-06T08:15:30.123456789Z");
    private static final Clock CLOCK = Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);

    private static final int BATCH_SIZE = 500;

    @Mock
    private NotificationMapper notificationMapper;

    @Test
    void deletesNothingWhenNoReadNotificationIsPastRetention() {
        NotificationCleanupTask task = task(Duration.ofDays(90));
        when(notificationMapper.deleteReadBefore(any(), anyInt())).thenReturn(0);

        task.cleanReadNotifications();

        verify(notificationMapper, times(1)).deleteReadBefore(any(), eq(BATCH_SIZE));
    }

    @Test
    void stopsAfterASingleBatchWhenItIsNotFull() {
        NotificationCleanupTask task = task(Duration.ofDays(90));
        when(notificationMapper.deleteReadBefore(any(), anyInt())).thenReturn(100);

        task.cleanReadNotifications();

        verify(notificationMapper, times(1)).deleteReadBefore(any(), eq(BATCH_SIZE));
    }

    @Test
    void keepsDeletingUntilBatchIsNotFull() {
        NotificationCleanupTask task = task(Duration.ofDays(90));
        when(notificationMapper.deleteReadBefore(any(), anyInt()))
                .thenReturn(BATCH_SIZE, BATCH_SIZE, 0);

        task.cleanReadNotifications();

        verify(notificationMapper, times(3)).deleteReadBefore(any(), eq(BATCH_SIZE));
    }

    @Test
    void cutoffIsTheClockTimeTruncatedToMillisMinusRetentionInUtc() {
        NotificationCleanupTask task = task(Duration.ofDays(90));
        when(notificationMapper.deleteReadBefore(any(), anyInt())).thenReturn(0);

        task.cleanReadNotifications();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notificationMapper).deleteReadBefore(cutoff.capture(), eq(BATCH_SIZE));
        assertThat(cutoff.getValue()).as("阈值 = 应用时钟（按毫秒截断）减保留期，UTC 时区")
                .isEqualTo(LocalDateTime.ofInstant(
                        CLOCK_INSTANT.truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofDays(90)),
                        ZoneOffset.UTC));
        assertThat(cutoff.getValue().getNano()).as("阈值精度与 DATETIME(3) 对齐（纳秒只到毫秒）")
                .isEqualTo(123_000_000);
    }

    /** 一个短保留期（下界 1h）同样只按配置推导，不额外兜底成默认 90 天。 */
    @Test
    void cutoffFollowsTheConfiguredRetention() {
        NotificationCleanupTask task = task(Duration.ofHours(1));
        when(notificationMapper.deleteReadBefore(any(), anyInt())).thenReturn(0);

        task.cleanReadNotifications();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notificationMapper).deleteReadBefore(cutoff.capture(), anyInt());
        assertThat(cutoff.getValue()).isEqualTo(LocalDateTime.ofInstant(
                CLOCK_INSTANT.truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofHours(1)),
                ZoneOffset.UTC));
    }

    private NotificationCleanupTask task(Duration retention) {
        return new NotificationCleanupTask(
                notificationMapper, new NotificationProperties(retention), CLOCK);
    }
}
