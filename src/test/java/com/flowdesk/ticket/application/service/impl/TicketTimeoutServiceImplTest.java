package com.flowdesk.ticket.application.service.impl;

import com.flowdesk.attachment.application.AttachmentValidator;
import com.flowdesk.attachment.config.AttachmentProperties;
import com.flowdesk.attachment.infrastructure.AttachmentStorage;
import com.flowdesk.attachment.mapper.AttachmentMapper;
import com.flowdesk.attachment.support.AttachmentPropertiesFixture;
import com.flowdesk.notification.application.command.PublishNotificationCommand;
import com.flowdesk.notification.domain.NotificationCategory;
import com.flowdesk.ticket.application.port.CategoryAvailabilityPort;
import com.flowdesk.ticket.application.port.CurrentRequesterPort;
import com.flowdesk.ticket.application.port.NotificationPort;
import com.flowdesk.ticket.application.port.TicketClaimantPort;
import com.flowdesk.ticket.application.port.TicketReadPermissionPort;
import com.flowdesk.ticket.config.TicketProperties;
import com.flowdesk.ticket.domain.Ticket;
import com.flowdesk.ticket.infrastructure.persistence.TicketDetailRow;
import com.flowdesk.ticket.domain.TicketRecord;
import com.flowdesk.ticket.infrastructure.persistence.TicketReminderRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketTimeoutRow;
import com.flowdesk.ticket.mapper.TicketAttachmentMapper;
import com.flowdesk.ticket.mapper.TicketDailySequenceMapper;
import com.flowdesk.ticket.mapper.TicketMapper;
import com.flowdesk.ticket.mapper.TicketParticipantMapper;
import com.flowdesk.ticket.mapper.TicketRecordMapper;
import com.flowdesk.ticket.mapper.TicketRelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 超时自动处置、到期前提醒与提醒回收的服务层规则（批次 2026-10-16）。
 *
 * <p>这三条链路此前<b>没有任何用例</b>——本类补的就是那段空白。它守的都是"沉默失败"型缺陷：
 * 条件更新命中 0 行时若仍发通知 / 写记录，会留下与工单状态不符的假事实；
 * 提醒去重键若不含期限，改期之后永远提醒不了第二次；回收若不限类别，
 * 会把业务事件通知一起删掉。这些在浏览器里都看不出来，只有断言替身收到的参数才拦得住。</p>
 *
 * <p>真库上的行为（条件更新的真实 0 行、{@code FOR UPDATE} 的锁语义）由
 * {@code TicketTimeoutServiceIT} 覆盖；本类只钉服务层的调用协议与参数构造。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TicketTimeoutServiceImplTest {

    private static final Instant CLOCK_INSTANT = Instant.parse("2026-10-06T08:15:30.123456789Z");
    private static final Clock CLOCK = Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(
            CLOCK_INSTANT.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);

    private static final long TICKET_ID = 2001L;
    private static final long REQUESTER_ID = 11L;
    private static final long IT_USER_ID = 22L;
    private static final long CATEGORY_ID = 3L;
    private static final String TICKET_NO = "FD-20261006-002";

    private static final String WAITING_FOR_REQUESTER = "WAITING_FOR_REQUESTER";
    private static final String WAITING_FOR_CONFIRMATION = "WAITING_FOR_CONFIRMATION";

    private static final Duration LEAD_ONE_DAY = Duration.ofDays(1);
    private static final TicketProperties PROPERTIES = new TicketProperties(
            Duration.ofDays(7), Duration.ofDays(7), Duration.ofDays(3), LEAD_ONE_DAY);

    private static final AttachmentProperties ATTACHMENT_PROPERTIES =
            AttachmentPropertiesFixture.local(
                    Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath());

    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private TicketDailySequenceMapper ticketDailySequenceMapper;
    @Mock
    private TicketRecordMapper ticketRecordMapper;
    @Mock
    private TicketParticipantMapper ticketParticipantMapper;
    @Mock
    private TicketRelationMapper ticketRelationMapper;
    @Mock
    private TicketAttachmentMapper ticketAttachmentMapper;
    @Mock
    private AttachmentStorage attachmentStorage;
    @Mock
    private AttachmentMapper attachmentMapper;
    @Mock
    private NotificationPort notificationPort;
    @Mock
    private CurrentRequesterPort currentRequesterPort;
    @Mock
    private CategoryAvailabilityPort categoryAvailabilityPort;
    @Mock
    private TicketReadPermissionPort ticketReadPermissionPort;
    @Mock
    private TicketClaimantPort ticketClaimPort;
    @Mock
    private PlatformTransactionManager transactionManager;

    private TicketServiceImpl service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new TicketServiceImpl(
                CLOCK,
                ticketMapper,
                ticketDailySequenceMapper,
                ticketRecordMapper,
                currentRequesterPort,
                categoryAvailabilityPort,
                ticketReadPermissionPort,
                transactionManager,
                ticketClaimPort,
                ticketParticipantMapper,
                ticketRelationMapper,
                PROPERTIES,
                ticketAttachmentMapper,
                new AttachmentValidator(ATTACHMENT_PROPERTIES),
                attachmentStorage,
                attachmentMapper,
                ATTACHMENT_PROPERTIES,
                notificationPort);
    }

    // ---------- 自动完成（待确认超时） ----------

    /**
     * 命中候选并全部成功：条件更新、`record_seq` 快照读、时间线留痕、提醒回收、超时通知，
     * 五步都要发生，留痕必须是系统身份且不得带 actorUserId。
     */
    @Test
    void completeExpiredConfirmationsWritesSystemRecordAndPublishesTimeoutNotification() {
        TicketTimeoutRow candidate = timeoutRow(5L, NOW_UTC.minusMinutes(1));
        when(ticketMapper.selectExpiredForAction(
                eq(WAITING_FOR_CONFIRMATION), eq(NOW_UTC), eq(200))).thenReturn(List.of(candidate));
        when(ticketMapper.completeAutoConfirmTimeout(TICKET_ID, 5L, NOW_UTC)).thenReturn(1);

        Ticket updated = updatedTicket(WAITING_FOR_CONFIRMATION, NOW_UTC);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updated);
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        int completed = service.completeExpiredConfirmations(200);

        assertThat(completed).isEqualTo(1);

        ArgumentCaptor<TicketRecord> record = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(record.capture());
        assertThat(record.getValue().getRecordType()).isEqualTo("COMPLETION");
        assertThat(record.getValue().getActorType()).as("系统动作的 actor_type 固定 SYSTEM").isEqualTo("SYSTEM");
        assertThat(record.getValue().getActorUserId())
                .as("ck_ticket_record_actor_pair 要求 SYSTEM 时 actor_user_id 为空").isNull();
        assertThat(record.getValue().getCompletionMethod()).isEqualTo("AUTO_CONFIRM_TIMEOUT");
        assertThat(record.getValue().getFromStatus()).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(record.getValue().getToStatus()).isEqualTo("COMPLETED");
        assertThat(record.getValue().getSequenceNo()).isEqualTo(updated.getRecordSeq());

        verify(notificationPort).recallReminders(TICKET_ID, List.of(
                NotificationCategory.CONFIRMATION_DUE_SOON,
                NotificationCategory.SUPPLEMENT_DUE_SOON,
                NotificationCategory.CANCEL_REQUEST_DUE_SOON));
    }

    /**
     * 超时通知的收件人是提交人与最后负责人各一条，且两条共用同一个去重键
     * （{@code 类别:ticketId:endedAt 毫秒}）——提交人恰好也是负责人时不该出现第三条。
     */
    @Test
    void timeoutNotificationGoesToRequesterAndAssigneeWithTheSameDedupKey() {
        TicketTimeoutRow candidate = timeoutRow(5L, NOW_UTC.minusMinutes(1));
        when(ticketMapper.selectExpiredForAction(any(), any(), anyInt())).thenReturn(List.of(candidate));
        when(ticketMapper.completeAutoConfirmTimeout(anyLong(), anyLong(), any())).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID))
                .thenReturn(updatedTicket(WAITING_FOR_CONFIRMATION, NOW_UTC));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        service.completeExpiredConfirmations(200);

        ArgumentCaptor<PublishNotificationCommand> published =
                ArgumentCaptor.forClass(PublishNotificationCommand.class);
        verify(notificationPort, times(2)).publish(published.capture());

        List<PublishNotificationCommand> commands = published.getAllValues();
        assertThat(commands).extracting(PublishNotificationCommand::recipientId)
                .containsExactlyInAnyOrder(REQUESTER_ID, IT_USER_ID);
        assertThat(commands).allSatisfy(command ->
                assertThat(command.category()).isEqualTo(NotificationCategory.CONFIRMATION_TIMEOUT));
        String expectedKey = NotificationCategory.CONFIRMATION_TIMEOUT.name() + ":" + TICKET_ID + ":"
                + NOW_UTC.toInstant(ZoneOffset.UTC).toEpochMilli();
        assertThat(commands).extracting(PublishNotificationCommand::dedupKey)
                .as("同一事件的两次调用必须用同一个键，否则同一次超时会各发一条")
                .containsOnly(expectedKey);
    }

    /**
     * 条件更新命中 0 行（已被人工动作抢先）：不计入完成数、不写记录、不发通知。
     *
     * <p>这条与"静默跳过"的口径一一对应——超时任务没有调用方要提示，命中 0 行不是错误；
     * 但如果它顺手发了通知，收件人就会收到一条与工单状态完全不符的消息。</p>
     */
    @Test
    void completeExpiredConfirmationsSkipsSilentlyWhenConditionalUpdateMisses() {
        TicketTimeoutRow candidate = timeoutRow(5L, NOW_UTC.minusMinutes(1));
        when(ticketMapper.selectExpiredForAction(any(), any(), anyInt())).thenReturn(List.of(candidate));
        when(ticketMapper.completeAutoConfirmTimeout(TICKET_ID, 5L, NOW_UTC)).thenReturn(0);

        int completed = service.completeExpiredConfirmations(200);

        assertThat(completed).isZero();
        verify(ticketRecordMapper, never()).insert(any(TicketRecord.class));
        verifyNoInteractions(notificationPort);
        verify(ticketMapper, never()).selectById(anyLong());
    }

    /** 候选缺 version 时直接跳过：没有版本就无法做条件更新，不能当成"已处置"。 */
    @Test
    void completeExpiredConfirmationsSkipsCandidateWithoutVersion() {
        TicketTimeoutRow candidate = timeoutRow(null, NOW_UTC.minusMinutes(1));
        when(ticketMapper.selectExpiredForAction(any(), any(), anyInt())).thenReturn(List.of(candidate));

        assertThat(service.completeExpiredConfirmations(200)).isZero();
        verify(ticketMapper, never()).completeAutoConfirmTimeout(anyLong(), anyLong(), any());
        verifyNoInteractions(notificationPort);
    }

    /**
     * 一张失败不影响其余：第一张的事务获取抛异常，第二张仍要处置成功。
     * 计数只统计真正完成的那些。
     */
    @Test
    void completeExpiredConfirmationsKeepsGoingWhenOneTicketFails() {
        TicketTimeoutRow first = timeoutRow(1L, NOW_UTC.minusMinutes(2));
        first.setId(9001L);
        TicketTimeoutRow second = timeoutRow(2L, NOW_UTC.minusMinutes(1));
        second.setId(9002L);
        when(ticketMapper.selectExpiredForAction(any(), any(), anyInt()))
                .thenReturn(List.of(first, second));
        //第一张的事务获取失败 → 单张崩溃；第二张照常拿到事务
        when(transactionManager.getTransaction(any()))
                .thenThrow(new IllegalStateException("第一张拿事务失败"))
                .thenReturn(new SimpleTransactionStatus());
        when(ticketMapper.completeAutoConfirmTimeout(9002L, 2L, NOW_UTC)).thenReturn(1);
        when(ticketMapper.selectById(9002L)).thenReturn(
                updatedTicket(9002L, WAITING_FOR_CONFIRMATION, NOW_UTC));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        int completed = service.completeExpiredConfirmations(200);

        assertThat(completed).as("失败的那张不计入，成功的那张仍要完成").isEqualTo(1);
        verify(ticketMapper).completeAutoConfirmTimeout(9002L, 2L, NOW_UTC);
    }

    /** 扫描上限原样透传：调度器给的批量大小不能被服务层改写。 */
    @Test
    void completeExpiredConfirmationsPassesTheLimitToTheScan() {
        when(ticketMapper.selectExpiredForAction(any(), any(), anyInt())).thenReturn(List.of());

        service.completeExpiredConfirmations(37);

        verify(ticketMapper).selectExpiredForAction(WAITING_FOR_CONFIRMATION, NOW_UTC, 37);
    }

    // ---------- 自动关闭（待补充超时） ----------

    /**
     * 自动关闭必须把原补充截止时间写进 `CLOSURE` 记录的 `deadline_at`——
     * 条件更新成功后 `ticket.action_deadline_at` 已清空，不写进留痕就没有第二处能看到
     * "当时要求什么时候补"。期限只能取自扫描快照。
     */
    @Test
    void closeExpiredSupplementsWritesClosureRecordWithTheOriginalDeadline() {
        LocalDateTime deadline = NOW_UTC.minusMinutes(1);
        TicketTimeoutRow candidate = timeoutRow(5L, deadline);
        when(ticketMapper.selectExpiredForAction(
                eq(WAITING_FOR_REQUESTER), eq(NOW_UTC), eq(200))).thenReturn(List.of(candidate));
        when(ticketMapper.completeAutoSupplementTimeout(TICKET_ID, 5L, NOW_UTC)).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID))
                .thenReturn(updatedTicket(WAITING_FOR_REQUESTER, NOW_UTC));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        int closed = service.closeExpiredSupplements(200);

        assertThat(closed).isEqualTo(1);
        ArgumentCaptor<TicketRecord> record = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(record.capture());
        assertThat(record.getValue().getRecordType()).isEqualTo("CLOSURE");
        assertThat(record.getValue().getDeadlineAt())
                .as("原补充截止时间取扫描快照，不从已清空的 ticket 行读").isEqualTo(deadline);
        assertThat(record.getValue().getCloseMethod()).isEqualTo("AUTO_SUPPLEMENT_TIMEOUT");
        assertThat(record.getValue().getCloseReason()).isEqualTo("REQUESTER_NO_RESPONSE");
        assertThat(record.getValue().getActorType()).isEqualTo("SYSTEM");
        assertThat(record.getValue().getActorUserId()).isNull();

        verify(notificationPort).recallReminders(TICKET_ID, List.of(
                NotificationCategory.CONFIRMATION_DUE_SOON,
                NotificationCategory.SUPPLEMENT_DUE_SOON,
                NotificationCategory.CANCEL_REQUEST_DUE_SOON));
    }

    /** 自动关闭的超时通知用 SUPPLEMENT_TIMEOUT，且发给提交人与最后负责人。 */
    @Test
    void closeExpiredSupplementsPublishesSupplementTimeoutNotification() {
        TicketTimeoutRow candidate = timeoutRow(5L, NOW_UTC.minusMinutes(1));
        when(ticketMapper.selectExpiredForAction(any(), any(), anyInt())).thenReturn(List.of(candidate));
        when(ticketMapper.completeAutoSupplementTimeout(anyLong(), anyLong(), any())).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID))
                .thenReturn(updatedTicket(WAITING_FOR_REQUESTER, NOW_UTC));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        service.closeExpiredSupplements(200);

        ArgumentCaptor<PublishNotificationCommand> published =
                ArgumentCaptor.forClass(PublishNotificationCommand.class);
        verify(notificationPort, times(2)).publish(published.capture());
        assertThat(published.getAllValues()).allSatisfy(command ->
                assertThat(command.category()).isEqualTo(NotificationCategory.SUPPLEMENT_TIMEOUT));
    }

    /** 自动关闭同样：命中 0 行时无记录、无通知、无回收。 */
    @Test
    void closeExpiredSupplementsSkipsSilentlyWhenConditionalUpdateMisses() {
        TicketTimeoutRow candidate = timeoutRow(5L, NOW_UTC.minusMinutes(1));
        when(ticketMapper.selectExpiredForAction(any(), any(), anyInt())).thenReturn(List.of(candidate));
        when(ticketMapper.completeAutoSupplementTimeout(TICKET_ID, 5L, NOW_UTC)).thenReturn(0);

        assertThat(service.closeExpiredSupplements(200)).isZero();
        verify(ticketRecordMapper, never()).insert(any(TicketRecord.class));
        verifyNoInteractions(notificationPort);
    }

    /** 自动关闭扫描的状态是「待补充」，且限制原样透传。 */
    @Test
    void closeExpiredSupplementsScansWaitingForRequester() {
        when(ticketMapper.selectExpiredForAction(any(), any(), anyInt())).thenReturn(List.of());

        service.closeExpiredSupplements(11);

        verify(ticketMapper).selectExpiredForAction(WAITING_FOR_REQUESTER, NOW_UTC, 11);
    }

    // ---------- 到期前提醒 ----------

    /**
     * 三段提醒各发一条：确认 / 补充发给提交人，撤销请求发给当前负责人。
     *
     * <p>扫描窗口是 {@code (now, now + lead]}——提前量 1 天，因此上界是 {@code NOW+1d}。
     * 三条的收件人分岔是这一批最容易被写错的地方（撤销请求那条若发给提交人，
     * 就是一个"只能批准的人收不到、不能批准的人收到"的静默错误）。</p>
     */
    @Test
    void publishDueSoonRemindersPublishesThreeSegmentsToTheirOwnRecipients() {
        LocalDateTime confirmationDeadline = NOW_UTC.plusHours(20);
        LocalDateTime supplementDeadline = NOW_UTC.plusHours(10);
        LocalDateTime cancelDeadline = NOW_UTC.plusHours(5);

        when(ticketMapper.selectDueSoonForAction(
                eq(WAITING_FOR_CONFIRMATION), eq(NOW_UTC), eq(NOW_UTC.plus(LEAD_ONE_DAY)), eq(200)))
                .thenReturn(List.of(reminderRow(TICKET_ID, TICKET_NO, REQUESTER_ID, IT_USER_ID,
                        confirmationDeadline)));
        when(ticketMapper.selectDueSoonForAction(
                eq(WAITING_FOR_REQUESTER), eq(NOW_UTC), eq(NOW_UTC.plus(LEAD_ONE_DAY)), eq(200)))
                .thenReturn(List.of(reminderRow(TICKET_ID, TICKET_NO, REQUESTER_ID, IT_USER_ID,
                        supplementDeadline)));
        when(ticketMapper.selectDueSoonForCancelRequest(
                eq(NOW_UTC), eq(NOW_UTC.plus(LEAD_ONE_DAY)), eq(200)))
                .thenReturn(List.of(reminderRow(TICKET_ID, TICKET_NO, REQUESTER_ID, IT_USER_ID,
                        cancelDeadline)));
        when(ticketMapper.selectDueSoonForActionForUpdate(
                eq(TICKET_ID), eq(WAITING_FOR_CONFIRMATION), eq(NOW_UTC), eq(NOW_UTC.plus(LEAD_ONE_DAY))))
                .thenReturn(reminderRow(TICKET_ID, TICKET_NO, REQUESTER_ID, IT_USER_ID,
                        confirmationDeadline));
        when(ticketMapper.selectDueSoonForActionForUpdate(
                eq(TICKET_ID), eq(WAITING_FOR_REQUESTER), eq(NOW_UTC), eq(NOW_UTC.plus(LEAD_ONE_DAY))))
                .thenReturn(reminderRow(TICKET_ID, TICKET_NO, REQUESTER_ID, IT_USER_ID,
                        supplementDeadline));
        when(ticketMapper.selectDueSoonForCancelRequestForUpdate(
                eq(TICKET_ID), eq(NOW_UTC), eq(NOW_UTC.plus(LEAD_ONE_DAY))))
                .thenReturn(reminderRow(TICKET_ID, TICKET_NO, REQUESTER_ID, IT_USER_ID,
                        cancelDeadline));

        int published = service.publishDueSoonReminders(200);

        assertThat(published).isEqualTo(3);

        ArgumentCaptor<PublishNotificationCommand> captor =
                ArgumentCaptor.forClass(PublishNotificationCommand.class);
        verify(notificationPort, times(3)).publish(captor.capture());

        PublishNotificationCommand confirmation = commandOf(captor.getAllValues(),
                NotificationCategory.CONFIRMATION_DUE_SOON);
        assertThat(confirmation.recipientId())
                .as("确认提醒发给提交人").isEqualTo(REQUESTER_ID);

        PublishNotificationCommand supplement = commandOf(captor.getAllValues(),
                NotificationCategory.SUPPLEMENT_DUE_SOON);
        assertThat(supplement.recipientId())
                .as("补充提醒发给提交人").isEqualTo(REQUESTER_ID);

        PublishNotificationCommand cancel = commandOf(captor.getAllValues(),
                NotificationCategory.CANCEL_REQUEST_DUE_SOON);
        assertThat(cancel.recipientId())
                .as("撤销请求提醒发给当前负责人").isEqualTo(IT_USER_ID);
    }

    /**
     * 去重键 = 类别 + 工单 + <b>命中窗口的那个期限</b>的毫秒值。
     *
     * <p>用期限而不是当前时间：同一期限扫多少轮键都一样，只留一条；期限被改写则产生新键，
     * 允许再提醒一次——这正是"改了期限就该重新提醒"的业务语义。</p>
     */
    @Test
    void reminderDedupKeyIsCategoryTicketAndDeadline() {
        LocalDateTime deadline = NOW_UTC.plusHours(20);
        when(ticketMapper.selectDueSoonForAction(
                eq(WAITING_FOR_CONFIRMATION), any(), any(), anyInt()))
                .thenReturn(List.of(reminderRow(TICKET_ID, TICKET_NO, REQUESTER_ID, IT_USER_ID, deadline)));
        when(ticketMapper.selectDueSoonForAction(
                eq(WAITING_FOR_REQUESTER), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(ticketMapper.selectDueSoonForCancelRequest(any(), any(), anyInt()))
                .thenReturn(List.of());
        when(ticketMapper.selectDueSoonForActionForUpdate(
                anyLong(), eq(WAITING_FOR_CONFIRMATION), any(), any()))
                .thenReturn(reminderRow(TICKET_ID, TICKET_NO, REQUESTER_ID, IT_USER_ID, deadline));

        service.publishDueSoonReminders(200);

        ArgumentCaptor<PublishNotificationCommand> captor =
                ArgumentCaptor.forClass(PublishNotificationCommand.class);
        verify(notificationPort).publish(captor.capture());

        String expected = NotificationCategory.CONFIRMATION_DUE_SOON.name() + ":" + TICKET_ID + ":"
                + deadline.toInstant(ZoneOffset.UTC).toEpochMilli();
        assertThat(captor.getValue().dedupKey()).isEqualTo(expected);
    }

    /** 正文带相对剩余时间与后果：1 天窗口内按小时档向下取整。 */
    @Test
    void reminderBodyCarriesRemainingTimeAndConsequence() {
        LocalDateTime deadline = NOW_UTC.plusMinutes(90);
        when(ticketMapper.selectDueSoonForAction(
                eq(WAITING_FOR_CONFIRMATION), any(), any(), anyInt()))
                .thenReturn(List.of(reminderRow(TICKET_ID, TICKET_NO, REQUESTER_ID, IT_USER_ID, deadline)));
        when(ticketMapper.selectDueSoonForAction(
                eq(WAITING_FOR_REQUESTER), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(ticketMapper.selectDueSoonForCancelRequest(any(), any(), anyInt()))
                .thenReturn(List.of());
        when(ticketMapper.selectDueSoonForActionForUpdate(
                anyLong(), eq(WAITING_FOR_CONFIRMATION), any(), any()))
                .thenReturn(reminderRow(TICKET_ID, TICKET_NO, REQUESTER_ID, IT_USER_ID, deadline));

        service.publishDueSoonReminders(200);

        ArgumentCaptor<PublishNotificationCommand> captor =
                ArgumentCaptor.forClass(PublishNotificationCommand.class);
        verify(notificationPort).publish(captor.capture());
        assertThat(captor.getValue().body())
                .as("90 分钟向下取整 = 1 小时；向上取整会让人以为还有时间")
                .contains("还剩约 1 小时")
                .contains("自动完成该工单");
    }

    /**
     * 发布前重读未命中（期间已被人工动作抢先）→ 不发提醒、不计入发布数。
     * 扫描与重读之间隔着事务，这条是那道防线的唯一证据。
     */
    @Test
    void reminderIsSkippedWhenThePrePublishRereadMisses() {
        when(ticketMapper.selectDueSoonForAction(
                eq(WAITING_FOR_CONFIRMATION), any(), any(), anyInt()))
                .thenReturn(List.of(reminderRow(TICKET_ID, TICKET_NO, REQUESTER_ID, IT_USER_ID,
                        NOW_UTC.plusHours(20))));
        when(ticketMapper.selectDueSoonForAction(
                eq(WAITING_FOR_REQUESTER), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(ticketMapper.selectDueSoonForCancelRequest(any(), any(), anyInt()))
                .thenReturn(List.of());
        when(ticketMapper.selectDueSoonForActionForUpdate(
                anyLong(), eq(WAITING_FOR_CONFIRMATION), any(), any()))
                .thenReturn(null);

        assertThat(service.publishDueSoonReminders(200)).isZero();
        verify(notificationPort, never()).publish(any());
    }

    /**
     * 一段整体失败不影响另外两段：确认段查询抛异常，补充段仍要发出去。
     * 这是 {@code remindSafely} 与调度器"四段各自兜异常"之间的那一层。
     */
    @Test
    void reminderSegmentsAreIsolatedFromEachOther() {
        LocalDateTime supplementDeadline = NOW_UTC.plusHours(10);
        when(ticketMapper.selectDueSoonForAction(
                eq(WAITING_FOR_CONFIRMATION), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("确认段查询失败"));
        when(ticketMapper.selectDueSoonForAction(
                eq(WAITING_FOR_REQUESTER), any(), any(), anyInt()))
                .thenReturn(List.of(reminderRow(TICKET_ID, TICKET_NO, REQUESTER_ID, IT_USER_ID,
                        supplementDeadline)));
        when(ticketMapper.selectDueSoonForCancelRequest(any(), any(), anyInt()))
                .thenReturn(List.of());
        when(ticketMapper.selectDueSoonForActionForUpdate(
                anyLong(), eq(WAITING_FOR_REQUESTER), any(), any()))
                .thenReturn(reminderRow(TICKET_ID, TICKET_NO, REQUESTER_ID, IT_USER_ID,
                        supplementDeadline));

        int published = service.publishDueSoonReminders(200);

        assertThat(published).as("确认段失败不吞掉补充段").isEqualTo(1);
        ArgumentCaptor<PublishNotificationCommand> captor =
                ArgumentCaptor.forClass(PublishNotificationCommand.class);
        verify(notificationPort).publish(captor.capture());
        assertThat(captor.getValue().category()).isEqualTo(NotificationCategory.SUPPLEMENT_DUE_SOON);
    }

    /**
     * 收件人缺失（防御性分支）时不发站内信。
     *
     * <p>{@code publishReminder} 只在收件人为空时静默返回；但"重读命中"这一事实仍成立，
     * 因此该段计为已处理。两条 CHECK（必须有负责人 / 待决请求只能挂在这三态上）合起来已保证
     * 真库走不到这里，这只是防拆箱 NPE 的兜底——用例钉住的是"不发消息"这一后果。</p>
     */
    @Test
    void reminderIsNotPublishedWhenRecipientIsMissing() {
        LocalDateTime deadline = NOW_UTC.plusHours(20);
        when(ticketMapper.selectDueSoonForAction(
                eq(WAITING_FOR_CONFIRMATION), any(), any(), anyInt()))
                .thenReturn(List.of(reminderRow(TICKET_ID, TICKET_NO, null, IT_USER_ID, deadline)));
        when(ticketMapper.selectDueSoonForAction(
                eq(WAITING_FOR_REQUESTER), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(ticketMapper.selectDueSoonForCancelRequest(any(), any(), anyInt()))
                .thenReturn(List.of());
        when(ticketMapper.selectDueSoonForActionForUpdate(
                anyLong(), eq(WAITING_FOR_CONFIRMATION), any(), any()))
                .thenReturn(reminderRow(TICKET_ID, TICKET_NO, null, IT_USER_ID, deadline));

        service.publishDueSoonReminders(200);

        verify(notificationPort, never()).publish(any());
    }

    /** 扫描到的候选缺期限时是内部不一致，应显式失败而不是静默发一条没有去重键的提醒。 */
    @Test
    void reminderFailsLoudlyWhenDeadlineIsMissing() {
        when(ticketMapper.selectDueSoonForAction(
                eq(WAITING_FOR_CONFIRMATION), any(), any(), anyInt()))
                .thenReturn(List.of(reminderRow(TICKET_ID, TICKET_NO, REQUESTER_ID, IT_USER_ID, null)));
        when(ticketMapper.selectDueSoonForAction(
                eq(WAITING_FOR_REQUESTER), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(ticketMapper.selectDueSoonForCancelRequest(any(), any(), anyInt()))
                .thenReturn(List.of());
        when(ticketMapper.selectDueSoonForActionForUpdate(
                anyLong(), eq(WAITING_FOR_CONFIRMATION), any(), any()))
                .thenReturn(reminderRow(TICKET_ID, TICKET_NO, REQUESTER_ID, IT_USER_ID, null));

        //单条异常被循环兜住，因此整段仍返回 0 而不是把异常抛给调度器
        assertThat(service.publishDueSoonReminders(200)).isZero();
        verify(notificationPort, never()).publish(any());
    }

    // ---------- 提醒回收（撤销请求自然过期） ----------

    /**
     * 回收候选窗口 = {@code (now − 1d, now]}；逐张加锁重读，命中即按
     * {@code CANCEL_REQUEST_DUE_SOON} 单一类别删除。
     */
    @Test
    void recallExpiredCancelRemindersDeletesOnlyTheCancelReminder() {
        when(ticketMapper.selectExpiredCancelRequestIds(
                eq(NOW_UTC), eq(NOW_UTC.minusDays(1)), eq(200))).thenReturn(List.of(TICKET_ID));
        when(ticketMapper.lockIfCancelRequestExpired(TICKET_ID, NOW_UTC)).thenReturn(TICKET_ID);

        int recalled = service.recallExpiredCancelReminders(200);

        assertThat(recalled).isEqualTo(1);
        verify(notificationPort).recallReminders(
                TICKET_ID, List.of(NotificationCategory.CANCEL_REQUEST_DUE_SOON));
    }

    /**
     * 重读未命中（期间已被重新发起 / 确认 / 关闭）→ 不删。
     * 这是"不误删新期限提醒"的防线：新请求发的是另一条 key 的提醒。
     */
    @Test
    void recallIsSkippedWhenTheLockedRereadMisses() {
        when(ticketMapper.selectExpiredCancelRequestIds(any(), any(), anyInt()))
                .thenReturn(List.of(TICKET_ID));
        when(ticketMapper.lockIfCancelRequestExpired(TICKET_ID, NOW_UTC)).thenReturn(null);

        assertThat(service.recallExpiredCancelReminders(200)).isZero();
        verify(notificationPort, never()).recallReminders(anyLong(), any());
    }

    /** 一张失败不影响其余：第一张拿锁抛异常，第二张仍要回收。 */
    @Test
    void recallKeepsGoingWhenOneTicketFails() {
        when(ticketMapper.selectExpiredCancelRequestIds(any(), any(), anyInt()))
                .thenReturn(List.of(9001L, 9002L));
        when(transactionManager.getTransaction(any()))
                .thenThrow(new IllegalStateException("第一张拿锁失败"))
                .thenReturn(new SimpleTransactionStatus());
        when(ticketMapper.lockIfCancelRequestExpired(9002L, NOW_UTC)).thenReturn(9002L);

        int recalled = service.recallExpiredCancelReminders(200);

        assertThat(recalled).isEqualTo(1);
        verify(notificationPort).recallReminders(
                9002L, List.of(NotificationCategory.CANCEL_REQUEST_DUE_SOON));
    }

    /** 回收窗口与上限原样透传：下界是 now − 1 天，不是 now。 */
    @Test
    void recallCandidateWindowIsOneDayBeforeNow() {
        when(ticketMapper.selectExpiredCancelRequestIds(any(), any(), anyInt()))
                .thenReturn(List.of());

        service.recallExpiredCancelReminders(13);

        verify(ticketMapper).selectExpiredCancelRequestIds(NOW_UTC, NOW_UTC.minusDays(1), 13);
    }

    // ---------- 已发布动作的提醒回收（步 7） ----------

    /**
     * 请求补充：确认窗口作废 + 补充窗口开启，两类一起回收（不是一个）。
     */
    @Test
    void requestSupplementRecallsBothConfirmationAndSupplementReminders() {
        stubProcessActor();
        stubVisible(detailRow("PROCESSING", IT_USER_ID, 3L));
        LocalDateTime deadline = NOW_UTC.plusDays(7);
        when(ticketMapper.requestSupplement(TICKET_ID, 3L, IT_USER_ID, deadline, NOW_UTC)).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(processingTicket(4L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        service.requestSupplement(TICKET_NO,
                new com.flowdesk.ticket.application.command.RequestSupplementCommand(3L, "请补充型号"));

        verify(notificationPort).recallReminders(TICKET_ID, List.of(
                NotificationCategory.CONFIRMATION_DUE_SOON,
                NotificationCategory.SUPPLEMENT_DUE_SOON));
    }

    /** 撤销请求成功发起：回收的是负责人侧那条撤销提醒，不误伤确认 / 补充。 */
    @Test
    void requestCancelRecallsOnlyTheCancelRequestReminder() {
        stubRequesterActor();
        stubVisible(detailRow("PROCESSING", IT_USER_ID, 3L));
        LocalDateTime deadline = NOW_UTC.plusDays(3);
        when(ticketMapper.requestCancel(TICKET_ID, 3L, REQUESTER_ID, "问题已解决",
                deadline, 1, NOW_UTC)).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(processingTicket(4L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        service.requestCancel(TICKET_NO,
                new com.flowdesk.ticket.application.command.RequestCancelCommand(3L, "问题已解决"));

        verify(notificationPort).recallReminders(
                TICKET_ID, List.of(NotificationCategory.CANCEL_REQUEST_DUE_SOON));
    }

    // ---------- 辅助 ----------

    private static PublishNotificationCommand commandOf(List<PublishNotificationCommand> commands,
                                                        NotificationCategory category) {
        return commands.stream()
                .filter(command -> command.category() == category)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有发出类别 " + category + " 的提醒"));
    }

    private void stubProcessActor() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_PROCESS")).thenReturn(true);
    }

    private void stubRequesterActor() {
        when(currentRequesterPort.currentUserId()).thenReturn(REQUESTER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_REQUESTER_ACTION")).thenReturn(true);
    }

    private void stubVisible(TicketDetailRow row) {
        when(ticketMapper.selectVisibleDetail(any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(row);
    }

    private static TicketTimeoutRow timeoutRow(Long version, LocalDateTime deadline) {
        TicketTimeoutRow row = new TicketTimeoutRow();
        row.setId(TICKET_ID);
        row.setVersion(version);
        row.setActionDeadlineAt(deadline);
        return row;
    }

    private static TicketReminderRow reminderRow(long ticketId, String ticketNo,
                                                 Long requesterId, Long assigneeId,
                                                 LocalDateTime deadline) {
        TicketReminderRow row = new TicketReminderRow();
        row.setId(ticketId);
        row.setTicketNo(ticketNo);
        row.setRequesterId(requesterId);
        row.setAssigneeId(assigneeId);
        row.setDeadlineAt(deadline);
        return row;
    }

    /** 超时处置后读回的快照：`record_seq` 已由条件更新自增，`ended_at` 就是这次事件的标识。 */
    private static Ticket updatedTicket(String status, LocalDateTime endedAt) {
        return updatedTicket(TICKET_ID, status, endedAt);
    }

    private static Ticket updatedTicket(long id, String status, LocalDateTime endedAt) {
        Ticket ticket = new Ticket();
        ticket.setId(id);
        ticket.setTicketNo(TICKET_NO);
        ticket.setRequesterId(REQUESTER_ID);
        ticket.setAssigneeId(IT_USER_ID);
        ticket.setCategoryId(CATEGORY_ID);
        ticket.setStatus(status);
        ticket.setRecordSeq(9);
        ticket.setVersion(6L);
        ticket.setEndedAt(endedAt);
        return ticket;
    }

    private static Ticket processingTicket(long version) {
        return processingTicket(version, "PROCESSING");
    }

    private static Ticket processingTicket(long version, String status) {
        Ticket ticket = new Ticket();
        ticket.setId(TICKET_ID);
        ticket.setTicketNo(TICKET_NO);
        ticket.setRequesterId(REQUESTER_ID);
        ticket.setAssigneeId(IT_USER_ID);
        ticket.setCategoryId(CATEGORY_ID);
        ticket.setStatus(status);
        ticket.setVersion(version);
        ticket.setRecordSeq((int) version + 1);
        return ticket;
    }

    private static TicketDetailRow detailRow(String status, Long assigneeId, long version) {
        TicketDetailRow row = new TicketDetailRow();
        row.setId(TICKET_ID);
        row.setTicketNo(TICKET_NO);
        row.setRequesterId(REQUESTER_ID);
        row.setAssigneeId(assigneeId);
        row.setStatus(status);
        row.setVersion(version);
        return row;
    }
}
