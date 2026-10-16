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
 * 站内信清理任务的批处理协议：两段口径（已读按 {@code read_at}、未读按 {@code created_at}）
 * 各自从同一个应用 UTC 时钟减自己的保留期推导阈值，每批 500 行并循环到某批不满为止。
 *
 * <p>被测类直接 {@code new} 出来；它守的是"两段的边界值从同一个时钟推导、循环不能提前停"——
 * 提前停会让堆积的通知永远删不完，而错把两段的保留期对调就会删掉还没过期的已读消息
 * （或让未读积压永不释放）。</p>
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

    // ---------- 已读一段（read_at 早于 retention） ----------

    @Test
    void deletesNothingWhenNoReadNotificationIsPastRetention() {
        NotificationCleanupTask task = task(Duration.ofDays(90), Duration.ofDays(3));
        when(notificationMapper.deleteReadBefore(any(), anyInt())).thenReturn(0);

        task.cleanNotifications();

        verify(notificationMapper, times(1)).deleteReadBefore(any(), eq(BATCH_SIZE));
    }

    @Test
    void stopsAfterASingleBatchWhenItIsNotFull() {
        NotificationCleanupTask task = task(Duration.ofDays(90), Duration.ofDays(3));
        when(notificationMapper.deleteReadBefore(any(), anyInt())).thenReturn(100);

        task.cleanNotifications();

        verify(notificationMapper, times(1)).deleteReadBefore(any(), eq(BATCH_SIZE));
    }

    @Test
    void keepsDeletingUntilBatchIsNotFull() {
        NotificationCleanupTask task = task(Duration.ofDays(90), Duration.ofDays(3));
        when(notificationMapper.deleteReadBefore(any(), anyInt()))
                .thenReturn(BATCH_SIZE, BATCH_SIZE, 0);

        task.cleanNotifications();

        verify(notificationMapper, times(3)).deleteReadBefore(any(), eq(BATCH_SIZE));
    }

    @Test
    void readCutoffIsTheClockTimeTruncatedToMillisMinusRetentionInUtc() {
        NotificationCleanupTask task = task(Duration.ofDays(90), Duration.ofDays(3));
        when(notificationMapper.deleteReadBefore(any(), anyInt())).thenReturn(0);

        task.cleanNotifications();

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
    void readCutoffFollowsTheConfiguredRetention() {
        NotificationCleanupTask task = task(Duration.ofHours(1), Duration.ofDays(3));
        when(notificationMapper.deleteReadBefore(any(), anyInt())).thenReturn(0);

        task.cleanNotifications();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notificationMapper).deleteReadBefore(cutoff.capture(), anyInt());
        assertThat(cutoff.getValue()).isEqualTo(LocalDateTime.ofInstant(
                CLOCK_INSTANT.truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofHours(1)),
                ZoneOffset.UTC));
    }

    // ---------- 未读一段（created_at 早于 unreadRetention） ----------

    /**
     * 口径变更的核心断言：未读也进入删除路径，阈值按 {@code unread-retention} 而非 {@code retention}。
     *
     * <p>2026-10-16 裁决取代了 V10 的「未读永不清理」——若这里退回旧口径（不调用
     * {@code deleteUnreadBefore} 或误用 90 天），收件人的未读徽标会被历史消息永久占住。</p>
     */
    @Test
    void unreadCutoffFollowsTheConfiguredUnreadRetention() {
        NotificationCleanupTask task = task(Duration.ofDays(90), Duration.ofDays(3));
        when(notificationMapper.deleteReadBefore(any(), anyInt())).thenReturn(0);
        when(notificationMapper.deleteUnreadBefore(any(), anyInt())).thenReturn(0);

        task.cleanNotifications();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notificationMapper).deleteUnreadBefore(cutoff.capture(), eq(BATCH_SIZE));
        assertThat(cutoff.getValue())
                .as("未读阈值用 unread-retention（3d）而不是已读的 retention（90d）")
                .isEqualTo(LocalDateTime.ofInstant(
                        CLOCK_INSTANT.truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofDays(3)),
                        ZoneOffset.UTC));
    }

    /** 未读一段同样"删到删不动为止"，不能只删一批就退出。 */
    @Test
    void unreadSegmentAlsoLoopsUntilBatchIsNotFull() {
        NotificationCleanupTask task = task(Duration.ofDays(90), Duration.ofDays(3));
        when(notificationMapper.deleteReadBefore(any(), anyInt())).thenReturn(0);
        when(notificationMapper.deleteUnreadBefore(any(), anyInt())).thenReturn(BATCH_SIZE, 0);

        task.cleanNotifications();

        verify(notificationMapper, times(2)).deleteUnreadBefore(any(), eq(BATCH_SIZE));
    }

    /**
     * 两段的阈值必须来自同一个瞬间：跨秒执行边界会漂。
     * 单独改时钟做不到，只能断言两段的入参减各自保留期后落在同一个 now 上。
     */
    @Test
    void bothCutoffsAreDerivedFromTheSameInstant() {
        NotificationCleanupTask task = task(Duration.ofDays(90), Duration.ofDays(3));
        when(notificationMapper.deleteReadBefore(any(), anyInt())).thenReturn(0);
        when(notificationMapper.deleteUnreadBefore(any(), anyInt())).thenReturn(0);

        task.cleanNotifications();

        ArgumentCaptor<LocalDateTime> readCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> unreadCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(notificationMapper).deleteReadBefore(readCutoff.capture(), anyInt());
        verify(notificationMapper).deleteUnreadBefore(unreadCutoff.capture(), anyInt());

        assertThat(readCutoff.getValue().plus(Duration.ofDays(90)))
                .as("已读阈值 + retention 与未读阈值 + unreadRetention 必须相等（同一个 now）")
                .isEqualTo(unreadCutoff.getValue().plus(Duration.ofDays(3)));
    }

    /** 已读一段删除行数为 0 时，未读一段仍必须跑：两段互不为前提。 */
    @Test
    void unreadSegmentRunsEvenWhenNoReadNotificationWasDeleted() {
        NotificationCleanupTask task = task(Duration.ofDays(90), Duration.ofDays(3));
        when(notificationMapper.deleteReadBefore(any(), anyInt())).thenReturn(0);
        when(notificationMapper.deleteUnreadBefore(any(), anyInt())).thenReturn(7);

        task.cleanNotifications();

        verify(notificationMapper).deleteUnreadBefore(any(), eq(BATCH_SIZE));
    }

    private NotificationCleanupTask task(Duration retention, Duration unreadRetention) {
        return new NotificationCleanupTask(
                notificationMapper, new NotificationProperties(retention, unreadRetention), CLOCK);
    }
}
