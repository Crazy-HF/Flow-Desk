package com.flowdesk.notification.application.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.notification.application.command.PublishNotificationCommand;
import com.flowdesk.notification.application.port.CurrentRecipientPort;
import com.flowdesk.notification.application.query.NotificationQuery;
import com.flowdesk.notification.application.result.NotificationResult;
import com.flowdesk.notification.application.result.NotificationUnreadCountResult;
import com.flowdesk.notification.domain.Notification;
import com.flowdesk.notification.domain.NotificationCategory;
import com.flowdesk.notification.mapper.NotificationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 站内信用例实现的业务规则：发布时逐列组装且落库时间只来自注入时钟、本人范围读取、
 * 归属判定与幂等标记。
 *
 * <p>本类是纯替身测试，被测类直接 {@code new} 出来；它守的是"通知与收件人绑定在一起"这条边界——
 * 收件人永远来自认证上下文，不接受调用方传入，越权与不存在统一 404。</p>
 */
@ExtendWith(MockitoExtension.class)
// 多个用例共享收件人桩，未使用的桩不应判定为失败。
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceImplTest {

    /** 故意带纳秒，用于断言落库前按毫秒截断。 */
    private static final Instant CLOCK_INSTANT = Instant.parse("2026-10-06T08:15:30.123456789Z");
    private static final Clock CLOCK = Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime NOW_UTC =
            LocalDateTime.ofInstant(CLOCK_INSTANT.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);

    private static final long RECIPIENT_ID = 42L;
    private static final long OTHER_RECIPIENT_ID = 77L;
    private static final long TICKET_ID = 1001L;
    private static final String TICKET_NO = "FD-20261006-001";
    private static final String TITLE = "工单 FD-20261006-001 需要你补充信息";
    private static final String BODY = "当前负责人请求你补充信息。";

    @Mock
    private NotificationMapper notificationMapper;

    @Mock
    private CurrentRecipientPort currentRecipientPort;

    private NotificationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NotificationServiceImpl(notificationMapper, currentRecipientPort, CLOCK);
    }

    // ---------- publish ----------

    @Test
    void publishAssemblesNotificationFromCommandAndStampsTheClock() {
        when(notificationMapper.insert(any(Notification.class))).thenReturn(1);

        service.publish(new PublishNotificationCommand(
                RECIPIENT_ID, NotificationCategory.SUPPLEMENT_REQUESTED,
                TITLE, BODY, TICKET_ID, TICKET_NO, null));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        Notification notification = captor.getValue();
        assertThat(notification.getId()).as("自增主键由库层回填，发布时不由服务端指定").isNull();
        assertThat(notification.getRecipientId()).isEqualTo(RECIPIENT_ID);
        assertThat(notification.getCategory()).as("枚举落库为 name 字符串")
                .isEqualTo("SUPPLEMENT_REQUESTED");
        assertThat(notification.getTitle()).isEqualTo(TITLE);
        assertThat(notification.getBody()).isEqualTo(BODY);
        assertThat(notification.getTicketId()).isEqualTo(TICKET_ID);
        assertThat(notification.getTicketNo()).isEqualTo(TICKET_NO);
        assertThat(notification.getDedupKey()).as("普通业务通知不带提醒去重键").isNull();
        assertThat(notification.getReadAt()).as("新通知一律未读").isNull();
        assertThat(notification.getCreatedAt()).as("落库时间来自注入时钟并按毫秒截断")
                .isEqualTo(NOW_UTC);
    }

    /** 正文可为空：只有提醒类通知才有正文，业务通知允许没有。 */
    @Test
    void publishAllowsAnEmptyBodyAndAKeepsTheCategoryCode() {
        when(notificationMapper.insert(any(Notification.class))).thenReturn(1);

        service.publish(new PublishNotificationCommand(
                RECIPIENT_ID, NotificationCategory.CANCEL_APPROVED,
                TITLE, null, null, null, null));

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationMapper).insert(captor.capture());
        assertThat(captor.getValue().getBody()).isNull();
        assertThat(captor.getValue().getTicketId()).isNull();
        assertThat(captor.getValue().getTicketNo()).isNull();
        assertThat(captor.getValue().getCategory()).isEqualTo("CANCEL_APPROVED");
    }

    @Test
    void publishFailsWhenInsertAffectsNoRow() {
        when(notificationMapper.insert(any(Notification.class))).thenReturn(0);

        assertThatThrownBy(() -> service.publish(new PublishNotificationCommand(
                RECIPIENT_ID, NotificationCategory.RESOLUTION_SUBMITTED,
                TITLE, BODY, TICKET_ID, TICKET_NO, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("站内信写入失败");
    }

    // ---------- page ----------

    @Test
    void pageWithoutUnreadOnlyReadsTheFullListForTheCurrentUser() {
        when(currentRecipientPort.currentUserId()).thenReturn(RECIPIENT_ID);
        when(notificationMapper.selectRecipientPage(any(), anyLong())).thenReturn(pageOf(sampleNotification()));

        NotificationQuery query = new NotificationQuery();
        PageResult<NotificationResult> result = service.page(query);

        verify(notificationMapper).selectRecipientPage(any(), eq(RECIPIENT_ID));
        verify(notificationMapper, never()).selectUnreadRecipientPage(any(), anyLong());
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().id()).isEqualTo(9L);
        assertThat(result.items().getFirst().category()).isEqualTo("SUPPLEMENT_REQUESTED");
    }

    @ParameterizedTest(name = "unreadOnly={0} keeps the full-list query unless it is true")
    @ValueSource(booleans = {false})
    void pageWithUnreadOnlyFalseReadsTheFullList(boolean unreadOnly) {
        when(currentRecipientPort.currentUserId()).thenReturn(RECIPIENT_ID);
        when(notificationMapper.selectRecipientPage(any(), anyLong())).thenReturn(pageOf(sampleNotification()));

        NotificationQuery query = new NotificationQuery();
        query.setUnreadOnly(unreadOnly);
        service.page(query);

        verify(notificationMapper).selectRecipientPage(any(), eq(RECIPIENT_ID));
        verify(notificationMapper, never()).selectUnreadRecipientPage(any(), anyLong());
    }

    @Test
    void pageWithUnreadOnlyTrueReadsTheUnreadListForTheCurrentUser() {
        when(currentRecipientPort.currentUserId()).thenReturn(RECIPIENT_ID);
        when(notificationMapper.selectUnreadRecipientPage(any(), anyLong())).thenReturn(pageOf(sampleNotification()));

        NotificationQuery query = new NotificationQuery();
        query.setUnreadOnly(Boolean.TRUE);
        service.page(query);

        verify(notificationMapper).selectUnreadRecipientPage(any(), eq(RECIPIENT_ID));
        verify(notificationMapper, never()).selectRecipientPage(any(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageCarriesTheQueryPagingParametersIntoTheMapper() {
        when(currentRecipientPort.currentUserId()).thenReturn(RECIPIENT_ID);
        when(notificationMapper.selectRecipientPage(any(), anyLong())).thenReturn(pageOf(sampleNotification()));

        NotificationQuery query = new NotificationQuery();
        query.setPage(2);
        query.setSize(50);
        service.page(query);

        ArgumentCaptor<Page<Notification>> captor = ArgumentCaptor.forClass(Page.class);
        verify(notificationMapper).selectRecipientPage(captor.capture(), eq(RECIPIENT_ID));
        assertThat(captor.getValue().getCurrent()).isEqualTo(2);
        assertThat(captor.getValue().getSize()).isEqualTo(50);
    }

    /** 收件人只来自认证上下文：即使有别人传入的收件人 id 也不会被查询使用。 */
    @Test
    void pageAlwaysUsesTheAuthenticatedRecipient() {
        when(currentRecipientPort.currentUserId()).thenReturn(OTHER_RECIPIENT_ID);
        when(notificationMapper.selectRecipientPage(any(), anyLong())).thenReturn(pageOf());

        service.page(new NotificationQuery());

        verify(notificationMapper).selectRecipientPage(any(), eq(OTHER_RECIPIENT_ID));
        verify(notificationMapper, never()).selectRecipientPage(any(), eq(RECIPIENT_ID));
    }

    // ---------- unreadCount ----------

    @Test
    void unreadCountReturnsTheMapperCountForTheCurrentUser() {
        when(currentRecipientPort.currentUserId()).thenReturn(RECIPIENT_ID);
        when(notificationMapper.countUnread(RECIPIENT_ID)).thenReturn(3L);

        NotificationUnreadCountResult result = service.unreadCount();

        assertThat(result.unreadCount()).isEqualTo(3L);
        verify(notificationMapper).countUnread(RECIPIENT_ID);
    }

    // ---------- markRead ----------

    @Test
    void markReadReportsNotFoundForAnUnknownOrForeignNotification() {
        when(currentRecipientPort.currentUserId()).thenReturn(RECIPIENT_ID);
        when(notificationMapper.selectOwnedId(99L, RECIPIENT_ID)).thenReturn(null);

        ApiException exception = catchThrowableOfType(
                () -> service.markRead(99L), ApiException.class);

        assertThat(exception).isNotNull();
        assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception.code()).isEqualTo("NOTIFICATION_NOT_FOUND");
        verify(notificationMapper, never()).markRead(anyLong(), anyLong(), any());
    }

    @Test
    void markReadUpdatesAnOwnedUnreadNotificationWithTheClockTime() {
        when(currentRecipientPort.currentUserId()).thenReturn(RECIPIENT_ID);
        when(notificationMapper.selectOwnedId(9L, RECIPIENT_ID)).thenReturn(9L);

        service.markRead(9L);

        verify(notificationMapper).markRead(9L, RECIPIENT_ID, NOW_UTC);
    }

    /** 已读时条件更新影响 0 行：服务不报错，重复调用幂等。 */
    @Test
    void markReadIsIdempotentWhenTheUpdateAffectsNoRow() {
        when(currentRecipientPort.currentUserId()).thenReturn(RECIPIENT_ID);
        when(notificationMapper.selectOwnedId(9L, RECIPIENT_ID)).thenReturn(9L);
        when(notificationMapper.markRead(9L, RECIPIENT_ID, NOW_UTC)).thenReturn(0);

        service.markRead(9L);

        verify(notificationMapper).markRead(9L, RECIPIENT_ID, NOW_UTC);
    }

    // ---------- markAllRead ----------

    @Test
    void markAllReadUpdatesEveryUnreadNotificationOfTheCurrentUser() {
        when(currentRecipientPort.currentUserId()).thenReturn(RECIPIENT_ID);

        service.markAllRead();

        verify(notificationMapper).markAllRead(RECIPIENT_ID, NOW_UTC);
    }

    // ---------- 辅助 ----------

    private static Notification sampleNotification() {
        Notification notification = new Notification();
        notification.setId(9L);
        notification.setRecipientId(RECIPIENT_ID);
        notification.setCategory("SUPPLEMENT_REQUESTED");
        notification.setTitle(TITLE);
        notification.setBody(BODY);
        notification.setTicketId(TICKET_ID);
        notification.setTicketNo(TICKET_NO);
        notification.setReadAt(null);
        notification.setCreatedAt(NOW_UTC);
        return notification;
    }

    private static Page<Notification> pageOf(Notification... records) {
        Page<Notification> page = new Page<>(1, 20);
        page.setRecords(List.of(records));
        page.setTotal(records.length);
        return page;
    }
}
