package com.flowdesk.ticket.application.service.impl;

import com.flowdesk.common.exception.ApiException;
import com.flowdesk.ticket.application.command.AddProcessingRecordCommand;
import com.flowdesk.ticket.application.command.ClaimTicketCommand;
import com.flowdesk.ticket.application.command.ConfirmResolutionCommand;
import com.flowdesk.ticket.application.command.CreateTicketCommand;
import com.flowdesk.ticket.application.command.ReportUnresolvedCommand;
import com.flowdesk.ticket.application.command.SubmitResolutionCommand;
import com.flowdesk.ticket.application.command.WithdrawSupplementRequestCommand;
import com.flowdesk.ticket.application.port.CategoryAvailabilityPort;
import com.flowdesk.ticket.application.port.CurrentRequesterPort;
import com.flowdesk.ticket.application.port.TicketClaimantPort;
import com.flowdesk.ticket.application.port.TicketReadPermissionPort;
import com.flowdesk.ticket.application.result.TicketActionResult;
import com.flowdesk.ticket.application.result.TicketCreatedResult;
import com.flowdesk.ticket.application.result.TicketUserSummaryResult;
import com.flowdesk.ticket.config.TicketProperties;
import com.flowdesk.ticket.domain.Ticket;
import com.flowdesk.ticket.domain.TicketRecord;
import com.flowdesk.ticket.infrastructure.persistence.TicketDetailRow;
import com.flowdesk.ticket.mapper.TicketDailySequenceMapper;
import com.flowdesk.ticket.mapper.TicketMapper;
import com.flowdesk.ticket.mapper.TicketParticipantMapper;
import com.flowdesk.ticket.mapper.TicketRecordMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工单写动作服务的业务规则：幂等创建与并发兜底、领取时的权限/资格/并发判定、
 * 追加处理记录与提交解决结果的校验顺序、确认期限的配置来源，以及每个动作写入的
 * 不可变时间线记录。
 *
 * <p>契约见 {@code docs/api-design.md} 6.2 与 6.3、{@code docs/kickoff.md}；
 * 这里用替身固定"先校验领取资格再读可见性""冲突判定先于 400 内容校验""权限闸门先于
 * 提交人身份闸门"等顺序约束，并用 {@link Clock} 固定业务日（Asia/Shanghai）与
 * 落库时间（UTC）之间的时区差异。</p>
 *
 * <p>片 A 的两个"从等待态退回处理中"的动作（{@code withdrawSupplementRequest} 与
 * {@code reportUnresolved}）形状同源：都在条件更新的 {@code WHERE} 里同时判定状态、身份与版本，
 * 由 SQL 一并清空 {@code action_deadline_at}。因此这里对两者使用对称的用例集
 * （权限 → 可见性 → 状态/负责人或提交人 → 版本 → 原因长度 → 条件更新落败读回快照），
 * 差别只在身份列：撤回要求"本人是当前负责人"，反馈要求"本人是提交人，且权限闸门先于身份闸门"。</p>
 */
@ExtendWith(MockitoExtension.class)
// 多个用例共享"可见性行 + 事务管理器"替身，未使用的桩不应判定为失败。
@MockitoSettings(strictness = Strictness.LENIENT)
class TicketServiceImplTest {

    /** 故意带纳秒，用于断言落库前按毫秒截断。 */
    private static final Instant CLOCK_INSTANT = Instant.parse("2026-10-06T08:15:30.123456789Z");
    private static final Clock CLOCK = Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);
    private static final Instant NOW = CLOCK_INSTANT.truncatedTo(ChronoUnit.MILLIS);
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 10, 6);

    private static final long REQUESTER_ID = 42L;
    private static final long IT_USER_ID = 7L;
    private static final long OTHER_IT_USER_ID = 9L;
    private static final long TICKET_ID = 1001L;
    private static final long CATEGORY_ID = 3L;

    private static final String TICKET_NO = "FD-20261006-001";
    private static final String IT_DISPLAY_NAME = "演示 IT 支持人员";
    private static final String PENDING = "PENDING";
    private static final String PROCESSING = "PROCESSING";
    private static final String WAITING_FOR_REQUESTER = "WAITING_FOR_REQUESTER";
    private static final String WAITING_FOR_CONFIRMATION = "WAITING_FOR_CONFIRMATION";
    private static final String COMPLETED = "COMPLETED";
    private static final String HIGH = "HIGH";

    private static final String SUBMISSION_KEY = "3f1c9f4e-2a6b-4b7c-8d9e-0a1b2c3d4e5f";
    private static final TicketProperties DEFAULT_PROPERTIES =
            new TicketProperties(Duration.ofDays(7));

    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private TicketDailySequenceMapper ticketDailySequenceMapper;
    @Mock
    private TicketRecordMapper ticketRecordMapper;
    @Mock
    private TicketParticipantMapper ticketParticipantMapper;
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
        service = service(CLOCK, DEFAULT_PROPERTIES);
    }

    // ---------- create ----------

    /** 同一提交键再次提交返回首次创建快照：不吃当前状态，也不重复触发编号与落库。 */
    @Test
    void createReturnsFirstCreationSnapshotWhenSubmissionKeyAlreadyExists() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 1, 1, 0);
        when(currentRequesterPort.currentUserId()).thenReturn(REQUESTER_ID);
        when(ticketMapper.selectCreationByRequesterAndSubmissionKey(REQUESTER_ID, SUBMISSION_KEY))
                .thenReturn(existingTicket("FD-20261001-004", createdAt, COMPLETED, 9L));

        TicketCreatedResult result = service.create(createCommand(SUBMISSION_KEY));

        assertThat(result.ticketNo()).as("重复提交返回首次创建的工单编号")
                .isEqualTo("FD-20261001-004");
        assertThat(result.status()).as("重复提交固定返回 PENDING，不暴露工单当前状态")
                .isEqualTo(PENDING);
        assertThat(result.version()).as("重复提交固定返回首次创建时的版本 0").isZero();
        assertThat(result.createdAt()).as("创建时间取既有行而不是当前时钟")
                .isEqualTo(createdAt.atOffset(ZoneOffset.UTC));
        verifyNoInteractions(categoryAvailabilityPort, ticketDailySequenceMapper, ticketRecordMapper);
        verify(ticketMapper, never()).insert(any(Ticket.class));
    }

    /** 提交键是客户端幂等键，大小写不敏感：查询与落库都用 UUID 的规范小写形式。 */
    @Test
    void createNormalisesSubmissionKeyToCanonicalLowercaseUuid() {
        String upperCaseKey = SUBMISSION_KEY.toUpperCase(Locale.ROOT);
        assertThat(UUID.fromString(upperCaseKey).toString())
                .as("用例前提：大写提交键确实不是 UUID 的规范形式")
                .isEqualTo(SUBMISSION_KEY);

        when(currentRequesterPort.currentUserId()).thenReturn(REQUESTER_ID);
        when(ticketMapper.selectCreationByRequesterAndSubmissionKey(REQUESTER_ID, SUBMISSION_KEY))
                .thenReturn(existingTicket("FD-20261001-004",
                        LocalDateTime.of(2026, 10, 1, 1, 0), COMPLETED, 9L));

        service.create(createCommand(upperCaseKey));

        verify(ticketMapper).selectCreationByRequesterAndSubmissionKey(REQUESTER_ID, SUBMISSION_KEY);
    }

    @Test
    void createRejectsDisabledCategoryBeforeAllocatingSequence() {
        when(currentRequesterPort.currentUserId()).thenReturn(REQUESTER_ID);
        when(ticketMapper.selectCreationByRequesterAndSubmissionKey(REQUESTER_ID, SUBMISSION_KEY))
                .thenReturn(null);
        when(categoryAvailabilityPort.isEnabled(CATEGORY_ID)).thenReturn(false);

        assertApiException(() -> service.create(createCommand(SUBMISSION_KEY)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketDailySequenceMapper, ticketRecordMapper);
        verify(ticketMapper, never()).insert(any(Ticket.class));
    }

    @Test
    void createPersistsTicketAndCreateRecordForFirstSubmission() {
        stubFirstCreation(1L);
        ArgumentCaptor<Ticket> tickets = ArgumentCaptor.forClass(Ticket.class);
        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);

        TicketCreatedResult result = service.create(createCommand(SUBMISSION_KEY));

        verify(ticketMapper).insert(tickets.capture());
        Ticket ticket = tickets.getValue();
        assertThat(ticket.getTicketNo()).as("编号 = FD + 业务日 + 三位序号").isEqualTo(TICKET_NO);
        assertThat(ticket.getSubmissionKey()).isEqualTo(SUBMISSION_KEY);
        assertThat(ticket.getRequesterId()).isEqualTo(REQUESTER_ID);
        assertThat(ticket.getTitle()).as("标题在命令构造器中去除了首尾空白")
                .isEqualTo("打印机无法连接");
        assertThat(ticket.getDescription()).isEqualTo("三楼打印机离线");
        assertThat(ticket.getCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(ticket.getPriority()).isEqualTo(HIGH);
        assertThat(ticket.getStatus()).isEqualTo(PENDING);
        assertThat(ticket.getRecordSeq()).as("创建记录占用序号 1").isEqualTo(1);
        assertThat(ticket.getVersion()).isZero();
        assertThat(ticket.getCreatedAt()).isEqualTo(NOW_UTC);
        assertThat(ticket.getUpdatedAt()).isEqualTo(NOW_UTC);

        verify(ticketRecordMapper).insert(records.capture());
        TicketRecord record = records.getValue();
        assertThat(record.getTicketId()).as("记录挂到插入后回填的工单 ID 上").isEqualTo(TICKET_ID);
        assertThat(record.getSequenceNo()).isEqualTo(1);
        assertThat(record.getRecordType()).isEqualTo("CREATE");
        assertThat(record.getActorType()).isEqualTo("USER");
        assertThat(record.getActorUserId()).isEqualTo(REQUESTER_ID);
        assertThat(record.getFromStatus()).as("创建没有前置状态").isNull();
        assertThat(record.getToStatus()).isEqualTo(PENDING);
        assertThat(record.getToCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(record.getToPriority()).isEqualTo(HIGH);
        assertThat(record.getCreatedAt()).isEqualTo(NOW_UTC);

        assertThat(result.ticketNo()).isEqualTo(TICKET_NO);
        assertThat(result.status()).isEqualTo(PENDING);
        assertThat(result.version()).isZero();
        assertThat(result.createdAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    }

    /** 业务日按 Asia/Shanghai 计算，落库时间按 UTC：UTC 16:30 已经属于北京次日。 */
    @Test
    void createUsesShanghaiBusinessDateForTicketNoAndUtcForStoredTimestamps() {
        Clock shanghaiMidnight = Clock.fixed(Instant.parse("2026-10-06T16:30:00Z"), ZoneOffset.UTC);
        stubFirstCreation(1L);

        TicketCreatedResult result = service(shanghaiMidnight, DEFAULT_PROPERTIES)
                .create(createCommand(SUBMISSION_KEY));

        assertThat(result.ticketNo()).as("北京时间 2026-10-07 00:30 应使用业务日 20261007")
                .isEqualTo("FD-20261007-001");
        assertThat(result.createdAt()).as("创建时间按 UTC 落库")
                .isEqualTo(Instant.parse("2026-10-06T16:30:00Z").atOffset(ZoneOffset.UTC));
        verify(ticketDailySequenceMapper).increment(LocalDate.of(2026, 10, 7));
        verify(ticketDailySequenceMapper).selectCurrentForUpdate(LocalDate.of(2026, 10, 7));
        ArgumentCaptor<Ticket> tickets = ArgumentCaptor.forClass(Ticket.class);
        verify(ticketMapper).insert(tickets.capture());
        assertThat(tickets.getValue().getCreatedAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 6, 16, 30));
    }

    @Test
    void createUsesUtcCalendarDayWhileShanghaiIsStillInDaytime() {
        Clock shanghaiDaytime = Clock.fixed(Instant.parse("2026-10-06T02:00:00Z"), ZoneOffset.UTC);
        stubFirstCreation(7L);

        TicketCreatedResult result = service(shanghaiDaytime, DEFAULT_PROPERTIES)
                .create(createCommand(SUBMISSION_KEY));

        assertThat(result.ticketNo()).as("北京时间 10:00 与 UTC 同一业务日，序号补零到三位")
                .isEqualTo("FD-20261006-007");
        assertThat(result.createdAt()).isEqualTo(
                LocalDateTime.of(2026, 10, 6, 2, 0).atOffset(ZoneOffset.UTC));
        verify(ticketDailySequenceMapper).increment(BUSINESS_DATE);
        verify(ticketDailySequenceMapper).selectCurrentForUpdate(BUSINESS_DATE);
    }

    @Test
    void createFailsWhenDailySequenceIsMissing() {
        stubFirstCreation(null);

        assertThatThrownBy(() -> service.create(createCommand(SUBMISSION_KEY)))
                .as("序号读不到不能退化成无编号工单")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工单每日序号分配失败");

        verify(ticketMapper, never()).insert(any(Ticket.class));
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void createFailsWhenDailySequenceIsNotPositive() {
        stubFirstCreation(0L);

        assertThatThrownBy(() -> service.create(createCommand(SUBMISSION_KEY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工单每日序号分配失败");

        verify(ticketMapper, never()).insert(any(Ticket.class));
    }

    @Test
    void createFailsWhenTicketInsertAffectsNoRow() {
        stubFirstCreation(1L);
        when(ticketMapper.insert(any(Ticket.class))).thenReturn(0);

        assertThatThrownBy(() -> service.create(createCommand(SUBMISSION_KEY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工单插入失败");

        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void createFailsWhenRecordInsertAffectsNoRow() {
        stubFirstCreation(1L);
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(0);

        assertThatThrownBy(() -> service.create(createCommand(SUBMISSION_KEY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("创建记录插入失败");
    }

    /** 并发提交同一提交键时唯一索引会拒绝第二次插入，此时必须回到既有行。 */
    @Test
    void createFallsBackToExistingTicketWhenInsertConflictsWithConcurrentSubmission() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 1, 1, 0);
        when(currentRequesterPort.currentUserId()).thenReturn(REQUESTER_ID);
        when(ticketMapper.selectCreationByRequesterAndSubmissionKey(REQUESTER_ID, SUBMISSION_KEY))
                .thenReturn(null, existingTicket("FD-20261001-004", createdAt, COMPLETED, 9L));
        when(categoryAvailabilityPort.isEnabled(CATEGORY_ID)).thenReturn(true);
        when(ticketDailySequenceMapper.selectCurrentForUpdate(any(LocalDate.class))).thenReturn(2L);
        doThrow(new DuplicateKeyException("duplicate submission key"))
                .when(ticketMapper).insert(any(Ticket.class));

        TicketCreatedResult result = service.create(createCommand(SUBMISSION_KEY));

        assertThat(result.ticketNo()).isEqualTo("FD-20261001-004");
        assertThat(result.status()).as("并发兜底同样返回首次创建快照").isEqualTo(PENDING);
        assertThat(result.version()).isZero();
        assertThat(result.createdAt()).isEqualTo(createdAt.atOffset(ZoneOffset.UTC));
        verify(ticketMapper, times(2))
                .selectCreationByRequesterAndSubmissionKey(REQUESTER_ID, SUBMISSION_KEY);
        verifyNoInteractions(ticketRecordMapper);
    }

    /**
     * 唯一索引冲突不一定来自提交键：查不到原提交就是可重试的业务冲突，
     * 既不能当成创建成功，也不能落成 500；原始异常保留为 cause 便于定位。
     */
    @Test
    void createMapsUnrelatedDuplicateKeyToRetryableConflict() {
        DuplicateKeyException duplicate = new DuplicateKeyException("duplicate ticket_no");
        when(currentRequesterPort.currentUserId()).thenReturn(REQUESTER_ID);
        when(ticketMapper.selectCreationByRequesterAndSubmissionKey(REQUESTER_ID, SUBMISSION_KEY))
                .thenReturn(null);
        when(categoryAvailabilityPort.isEnabled(CATEGORY_ID)).thenReturn(true);
        when(ticketDailySequenceMapper.selectCurrentForUpdate(any(LocalDate.class))).thenReturn(1L);
        doThrow(duplicate).when(ticketMapper).insert(any(Ticket.class));

        ApiException exception = assertApiException(
                () -> service.create(createCommand(SUBMISSION_KEY)),
                HttpStatus.CONFLICT, "TICKET_CREATE_CONFLICT");

        assertThat(exception).as("编号冲突不能被当作幂等命中").hasCause(duplicate);
        verify(ticketMapper, times(2))
                .selectCreationByRequesterAndSubmissionKey(REQUESTER_ID, SUBMISSION_KEY);
    }

    /** 非 HTTP 调用方可能给出 null 提交键，结果必须与 Controller 的 Bean Validation 一致。 */
    @Test
    void createRejectsMissingSubmissionKeyAsValidationFailure() {
        when(currentRequesterPort.currentUserId()).thenReturn(REQUESTER_ID);

        assertApiException(
                () -> service.create(createCommand(null)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketMapper, ticketDailySequenceMapper, ticketRecordMapper);
    }

    /** 非 UUID 的提交键同样归入 400，不能以 IllegalArgumentException 冒到 500。 */
    @Test
    void createRejectsMalformedSubmissionKeyAsValidationFailure() {
        when(currentRequesterPort.currentUserId()).thenReturn(REQUESTER_ID);

        assertApiException(
                () -> service.create(createCommand("not-a-uuid")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketMapper, ticketDailySequenceMapper, ticketRecordMapper);
    }

    // ---------- claim ----------

    @Test
    void claimRejectsActorWithoutClaimAuthority() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_CLAIM")).thenReturn(false);

        assertApiException(() -> service.claim(TICKET_NO, new ClaimTicketCommand(0L)),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verifyNoInteractions(ticketMapper, ticketClaimPort, ticketParticipantMapper, ticketRecordMapper);
    }

    /** 资格闸门在可见性之前：不合格的领取人不应拿到"工单是否存在"的信息。 */
    @Test
    void claimRejectsIneligibleClaimantBeforeReadingVisibility() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_CLAIM")).thenReturn(true);
        when(ticketClaimPort.lockEligibleClaimant(IT_USER_ID)).thenReturn(null);

        assertApiException(() -> service.claim(TICKET_NO, new ClaimTicketCommand(0L)),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verify(ticketMapper, never())
                .selectVisibleDetail(any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    void claimReportsNotFoundWhenTicketIsNotVisible() {
        stubEligibleClaimant();

        assertApiException(() -> service.claim(TICKET_NO, new ClaimTicketCommand(0L)),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verify(ticketMapper, never()).claimPending(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void claimRejectsTicketSubmittedByTheActor() {
        stubEligibleClaimant();
        when(ticketMapper.selectVisibleDetail(any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(detailRow(PENDING, null, 0L, IT_USER_ID));

        assertApiException(() -> service.claim(TICKET_NO, new ClaimTicketCommand(0L)),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verify(ticketMapper, never()).claimPending(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void claimReportsConflictWhenTicketIsNoLongerPending() {
        stubEligibleClaimant();
        stubVisible(detailRow(PROCESSING, null, 4L));

        ApiException exception = assertApiException(
                () -> service.claim(TICKET_NO, new ClaimTicketCommand(4L)),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("冲突响应携带可见快照版本").isEqualTo(4L);
        assertThat(exception.resourceStatus()).as("冲突响应携带可见快照状态")
                .isEqualTo(PROCESSING);
        verify(ticketMapper, never()).claimPending(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void claimReportsConflictWhenTicketAlreadyHasAssignee() {
        stubEligibleClaimant();
        stubVisible(detailRow(PENDING, OTHER_IT_USER_ID, 2L));

        ApiException exception = assertApiException(
                () -> service.claim(TICKET_NO, new ClaimTicketCommand(2L)),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(2L);
        assertThat(exception.resourceStatus()).isEqualTo(PENDING);
    }

    @Test
    void claimReportsConflictWhenCommandVersionIsStale() {
        stubEligibleClaimant();
        stubVisible(detailRow(PENDING, null, 3L));

        ApiException exception = assertApiException(
                () -> service.claim(TICKET_NO, new ClaimTicketCommand(2L)),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(3L);
        assertThat(exception.resourceStatus()).isEqualTo(PENDING);
        verify(ticketMapper, never()).claimPending(anyLong(), anyLong(), anyLong(), any());
    }

    /** 缺少版本的非 HTTP 调用与"版本过期"同一处理：409 冲突，不是 NPE。 */
    @Test
    void claimTreatsMissingVersionAsConflictInsteadOfFailing() {
        stubEligibleClaimant();
        stubVisible(detailRow(PENDING, null, 0L));

        ApiException exception = assertApiException(
                () -> service.claim(TICKET_NO, new ClaimTicketCommand(null)),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isZero();
        assertThat(exception.resourceStatus()).isEqualTo(PENDING);
        verify(ticketMapper, never()).claimPending(anyLong(), anyLong(), anyLong(), any());
    }

    /** 两名 IT 同时领取时条件更新只有一个胜者，失败方读回最新快照再返回 409。 */
    @Test
    void claimReportsConflictWithReloadedSnapshotWhenConditionalUpdateLoses() {
        stubEligibleClaimant();
        stubVisible(detailRow(PENDING, null, 0L));
        when(ticketMapper.claimPending(TICKET_ID, 0L, IT_USER_ID, NOW_UTC)).thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID))
                .thenReturn(conflictSnapshot(PROCESSING, 1L));

        ApiException exception = assertApiException(
                () -> service.claim(TICKET_NO, new ClaimTicketCommand(0L)),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("携带读回的最新版本").isEqualTo(1L);
        assertThat(exception.resourceStatus()).as("携带读回的最新状态").isEqualTo(PROCESSING);
        verifyNoInteractions(ticketRecordMapper, ticketParticipantMapper);
    }

    @Test
    void claimFailsWhenConflictSnapshotCannotBeRead() {
        stubEligibleClaimant();
        stubVisible(detailRow(PENDING, null, 0L));
        when(ticketMapper.claimPending(TICKET_ID, 0L, IT_USER_ID, NOW_UTC)).thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.claim(TICKET_NO, new ClaimTicketCommand(0L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("领取冲突后无法读取工单快照");
    }

    @Test
    void claimWritesAssignmentAndClaimRecordThenReturnsProcessingSnapshot() {
        TicketUserSummaryResult claimant = eligibleClaimant();
        stubEligibleClaimant();
        stubVisible(detailRow(PENDING, null, 0L));
        when(ticketMapper.claimPending(TICKET_ID, 0L, IT_USER_ID, NOW_UTC)).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID))
                .thenReturn(updatedTicket(TICKET_NO, 2, 1L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.claim(TICKET_NO, new ClaimTicketCommand(0L));

        verify(ticketMapper).claimPending(TICKET_ID, 0L, IT_USER_ID, NOW_UTC);
        verify(ticketParticipantMapper, times(1)).recordAssignment(TICKET_ID, IT_USER_ID, NOW_UTC);

        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        TicketRecord record = records.getValue();
        assertThat(record.getRecordType()).isEqualTo("CLAIM");
        assertThat(record.getSequenceNo()).as("序号取递增后的 recordSeq").isEqualTo(2);
        assertThat(record.getActorType()).isEqualTo("USER");
        assertThat(record.getActorUserId()).isEqualTo(IT_USER_ID);
        assertThat(record.getFromStatus()).isEqualTo(PENDING);
        assertThat(record.getToStatus()).isEqualTo(PROCESSING);
        assertThat(record.getToAssigneeId()).isEqualTo(IT_USER_ID);
        assertThat(record.getCreatedAt()).isEqualTo(NOW_UTC);

        assertThat(result.ticketNo()).isEqualTo(TICKET_NO);
        assertThat(result.status()).isEqualTo(PROCESSING);
        assertThat(result.assignee()).as("负责人摘要来自加锁校验的返回值").isEqualTo(claimant);
        assertThat(result.actionDeadlineAt()).as("处理中状态没有确认期限").isNull();
        assertThat(result.version()).isEqualTo(1L);
        assertThat(result.actionTime()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    }

    @Test
    void claimFailsWhenUpdatedTicketSnapshotIsMissing() {
        stubSuccessfulConditionalClaim();
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.claim(TICKET_NO, new ClaimTicketCommand(0L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("领取后无法读取工单快照");

        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void claimFailsWhenUpdatedRecordSeqIsMissing() {
        stubSuccessfulConditionalClaim();
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, null, 1L));

        assertThatThrownBy(() -> service.claim(TICKET_NO, new ClaimTicketCommand(0L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("领取后无法读取工单快照");
    }

    @Test
    void claimFailsWhenUpdatedVersionIsMissing() {
        stubSuccessfulConditionalClaim();
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 2, null));

        assertThatThrownBy(() -> service.claim(TICKET_NO, new ClaimTicketCommand(0L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("领取后无法读取工单快照");
    }

    // ---------- addProcessingRecord ----------

    @Test
    void addProcessingRecordRejectsActorWithoutProcessAuthority() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_PROCESS")).thenReturn(false);

        assertApiException(
                () -> service.addProcessingRecord(TICKET_NO, new AddProcessingRecordCommand(0L, "已更换网线")),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verifyNoInteractions(ticketMapper, ticketRecordMapper);
    }

    @Test
    void addProcessingRecordReportsNotFoundWhenTicketIsNotVisible() {
        stubProcessActor();

        assertApiException(
                () -> service.addProcessingRecord(TICKET_NO, new AddProcessingRecordCommand(0L, "已更换网线")),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verify(ticketMapper, never()).advanceAssigneeAction(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void addProcessingRecordRejectsActorWhoIsNotTheCurrentAssignee() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, OTHER_IT_USER_ID, 3L));

        ApiException exception = assertApiException(
                () -> service.addProcessingRecord(TICKET_NO, new AddProcessingRecordCommand(3L, "已更换网线")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(3L);
        assertThat(exception.resourceStatus()).isEqualTo(PROCESSING);
        verify(ticketMapper, never()).advanceAssigneeAction(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void addProcessingRecordRejectsTicketThatIsNotProcessing() {
        stubProcessActor();
        stubVisible(detailRow(PENDING, IT_USER_ID, 3L));

        assertApiException(
                () -> service.addProcessingRecord(TICKET_NO, new AddProcessingRecordCommand(3L, "已更换网线")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never()).advanceAssigneeAction(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void addProcessingRecordRejectsStaleVersion() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 4L));

        ApiException exception = assertApiException(
                () -> service.addProcessingRecord(TICKET_NO, new AddProcessingRecordCommand(3L, "已更换网线")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(4L);
        verify(ticketMapper, never()).advanceAssigneeAction(anyLong(), anyLong(), anyLong(), any());
    }

    /** 校验顺序：状态与版本冲突先于正文长度，空白正文 + 过期版本必须得到 409。 */
    @Test
    void addProcessingRecordReportsConflictBeforeValidatingBlankContent() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 4L));

        assertApiException(
                () -> service.addProcessingRecord(TICKET_NO, new AddProcessingRecordCommand(3L, "   ")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never()).advanceAssigneeAction(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void addProcessingRecordRejectsBlankContentWhenVersionMatches() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertApiException(
                () -> service.addProcessingRecord(TICKET_NO, new AddProcessingRecordCommand(3L, "   ")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).advanceAssigneeAction(anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    /** 非 HTTP 调用方给出 null 正文时与空正文同一处理：400，不是 NPE。 */
    @Test
    void addProcessingRecordRejectsMissingContentAsValidationFailure() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertApiException(
                () -> service.addProcessingRecord(TICKET_NO, new AddProcessingRecordCommand(3L, null)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).advanceAssigneeAction(anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void addProcessingRecordRejectsContentOverLimit() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertApiException(
                () -> service.addProcessingRecord(TICKET_NO,
                        new AddProcessingRecordCommand(3L, "a".repeat(10001))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).advanceAssigneeAction(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void addProcessingRecordReportsConflictWithReloadedSnapshotWhenConditionalUpdateLoses() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));
        when(ticketMapper.advanceAssigneeAction(TICKET_ID, 3L, IT_USER_ID, NOW_UTC)).thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID))
                .thenReturn(conflictSnapshot(PROCESSING, 4L));

        ApiException exception = assertApiException(
                () -> service.addProcessingRecord(TICKET_NO, new AddProcessingRecordCommand(3L, "已更换网线")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(4L);
        assertThat(exception.resourceStatus()).isEqualTo(PROCESSING);
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void addProcessingRecordFailsWhenConflictSnapshotCannotBeRead() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));
        when(ticketMapper.advanceAssigneeAction(TICKET_ID, 3L, IT_USER_ID, NOW_UTC)).thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.addProcessingRecord(TICKET_NO,
                new AddProcessingRecordCommand(3L, "已更换网线")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("处理记录冲突后无法读取工单快照");
    }

    /** 追加处理记录只推进版本与时间线：状态与负责人两侧都不变。 */
    @Test
    void addProcessingRecordAppendsProcessRecordWithoutChangingStatusOrAssignee() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));
        when(ticketMapper.advanceAssigneeAction(TICKET_ID, 3L, IT_USER_ID, NOW_UTC)).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 5, 4L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.addProcessingRecord(TICKET_NO,
                new AddProcessingRecordCommand(3L, "  已更换网线并复测  "));

        verify(ticketMapper).advanceAssigneeAction(TICKET_ID, 3L, IT_USER_ID, NOW_UTC);
        verify(ticketMapper, never()).claimPending(anyLong(), anyLong(), anyLong(), any());
        verify(ticketMapper, never()).submitResolution(anyLong(), anyLong(), anyLong(), any(), any());
        verify(ticketMapper, never()).confirmResolution(anyLong(), anyLong(), anyLong(), any());

        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        TicketRecord record = records.getValue();
        assertThat(record.getRecordType()).isEqualTo("PROCESS");
        assertThat(record.getSequenceNo()).as("序号取递增后的 recordSeq").isEqualTo(5);
        assertThat(record.getContent()).as("正文去除首尾空白后落库").isEqualTo("已更换网线并复测");
        assertThat(record.getFromStatus()).as("本动作不迁移状态").isEqualTo(PROCESSING);
        assertThat(record.getToStatus()).isEqualTo(PROCESSING);
        assertThat(record.getFromAssigneeId()).isNull();
        assertThat(record.getToAssigneeId()).as("本动作不改负责人").isNull();
        assertThat(record.getActorUserId()).isEqualTo(IT_USER_ID);
        assertThat(record.getCreatedAt()).isEqualTo(NOW_UTC);

        assertThat(result.ticketNo()).isEqualTo(TICKET_NO);
        assertThat(result.status()).as("状态保持 PROCESSING").isEqualTo(PROCESSING);
        assertThat(result.assignee()).as("负责人按可见快照原样返回")
                .isEqualTo(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        assertThat(result.actionDeadlineAt()).isNull();
        assertThat(result.version()).isEqualTo(4L);
        assertThat(result.actionTime()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    }

    // ---------- submitResolution ----------

    @Test
    void submitResolutionRejectsActorWithoutProcessAuthority() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_PROCESS")).thenReturn(false);

        assertApiException(
                () -> service.submitResolution(TICKET_NO, new SubmitResolutionCommand(0L, "已恢复")),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verifyNoInteractions(ticketMapper, ticketRecordMapper);
    }

    @Test
    void submitResolutionReportsNotFoundWhenTicketIsNotVisible() {
        stubProcessActor();

        assertApiException(
                () -> service.submitResolution(TICKET_NO, new SubmitResolutionCommand(0L, "已恢复")),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verify(ticketMapper, never()).submitResolution(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void submitResolutionRejectsActorWhoIsNotTheCurrentAssignee() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, OTHER_IT_USER_ID, 3L));

        ApiException exception = assertApiException(
                () -> service.submitResolution(TICKET_NO, new SubmitResolutionCommand(3L, "已恢复")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceStatus()).isEqualTo(PROCESSING);
        verify(ticketMapper, never()).submitResolution(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void submitResolutionRejectsStaleVersion() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 4L));

        assertApiException(
                () -> service.submitResolution(TICKET_NO, new SubmitResolutionCommand(3L, "已恢复")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never()).submitResolution(anyLong(), anyLong(), anyLong(), any(), any());
    }

    /** 解决结果缺失正文同样归入 400 校验分支，不能因为 null 变成 500。 */
    @Test
    void submitResolutionRejectsMissingContentAsValidationFailure() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertApiException(
                () -> service.submitResolution(TICKET_NO, new SubmitResolutionCommand(3L, null)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).submitResolution(anyLong(), anyLong(), anyLong(), any(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void submitResolutionReportsConflictWithReloadedSnapshotWhenConditionalUpdateLoses() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));
        when(ticketMapper.submitResolution(TICKET_ID, 3L, IT_USER_ID,
                NOW_UTC.plusDays(7), NOW_UTC)).thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID))
                .thenReturn(conflictSnapshot(COMPLETED, 6L));

        ApiException exception = assertApiException(
                () -> service.submitResolution(TICKET_NO, new SubmitResolutionCommand(3L, "已恢复")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(6L);
        assertThat(exception.resourceStatus()).isEqualTo(COMPLETED);
        verifyNoInteractions(ticketRecordMapper);
    }

    /** 确认期限 = 提交时刻 + 配置窗口，结果、条件更新入参与时间线记录三处必须一致。 */
    @Test
    void submitResolutionUsesDefaultSevenDayConfirmationWindow() {
        LocalDateTime deadline = NOW_UTC.plus(Duration.ofDays(7));
        stubSubmittableTicket(3L, deadline);

        TicketActionResult result = service.submitResolution(TICKET_NO,
                new SubmitResolutionCommand(3L, "已恢复"));

        verify(ticketMapper).submitResolution(TICKET_ID, 3L, IT_USER_ID, deadline, NOW_UTC);
        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        assertThat(records.getValue().getDeadlineAt())
                .as("时间线记录与条件更新使用同一期限").isEqualTo(deadline);
        assertThat(result.actionDeadlineAt())
                .as("返回结果的期限按 UTC 偏移").isEqualTo(deadline.atOffset(ZoneOffset.UTC));
    }

    @Test
    void submitResolutionUsesConfiguredConfirmationWindow() {
        LocalDateTime deadline = NOW_UTC.plus(Duration.ofHours(1));
        stubSubmittableTicket(3L, deadline);

        TicketActionResult result = service(CLOCK, new TicketProperties(Duration.ofHours(1)))
                .submitResolution(TICKET_NO, new SubmitResolutionCommand(3L, "已恢复"));

        verify(ticketMapper).submitResolution(TICKET_ID, 3L, IT_USER_ID, deadline, NOW_UTC);
        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        assertThat(records.getValue().getDeadlineAt()).isEqualTo(deadline);
        assertThat(result.actionDeadlineAt()).isEqualTo(deadline.atOffset(ZoneOffset.UTC));
    }

    @Test
    void submitResolutionMarksTicketWaitingForConfirmation() {
        LocalDateTime deadline = NOW_UTC.plus(Duration.ofDays(7));
        stubSubmittableTicket(3L, deadline);

        TicketActionResult result = service.submitResolution(TICKET_NO,
                new SubmitResolutionCommand(3L, "  已更换网线并复测  "));

        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        TicketRecord record = records.getValue();
        assertThat(record.getRecordType()).isEqualTo("RESOLUTION");
        assertThat(record.getSequenceNo()).isEqualTo(5);
        assertThat(record.getContent()).as("正文去除首尾空白后落库").isEqualTo("已更换网线并复测");
        assertThat(record.getFromStatus()).isEqualTo(PROCESSING);
        assertThat(record.getToStatus()).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(record.getActorUserId()).isEqualTo(IT_USER_ID);
        assertThat(record.getCreatedAt()).isEqualTo(NOW_UTC);

        assertThat(result.status()).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(result.assignee()).isEqualTo(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        assertThat(result.version()).isEqualTo(4L);
        assertThat(result.actionTime()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    }

    // ---------- confirmResolution ----------

    /** 权限闸门先于提交人身份闸门：没有权限时不能借响应区分"是否提交人"。 */
    @Test
    void confirmResolutionChecksRequesterPermissionBeforeIdentity() {
        when(currentRequesterPort.currentUserId()).thenReturn(OTHER_IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_REQUESTER_ACTION")).thenReturn(false);

        assertApiException(
                () -> service.confirmResolution(TICKET_NO, new ConfirmResolutionCommand(4L)),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verify(ticketMapper, never())
                .selectVisibleDetail(any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(ticketMapper, never()).confirmResolution(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void confirmResolutionReportsNotFoundWhenTicketIsNotVisible() {
        stubRequesterActor();

        assertApiException(
                () -> service.confirmResolution(TICKET_NO, new ConfirmResolutionCommand(4L)),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verify(ticketMapper, never()).confirmResolution(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void confirmResolutionReportsConflictWhenTicketIsNotWaitingForConfirmation() {
        stubRequesterActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 4L));

        assertApiException(
                () -> service.confirmResolution(TICKET_NO, new ConfirmResolutionCommand(4L)),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never()).confirmResolution(anyLong(), anyLong(), anyLong(), any());
    }

    /** 看得到工单但不是提交人时按冲突处理，不用 403 暴露"谁是提交人"。 */
    @Test
    void confirmResolutionReportsConflictWhenActorIsNotTheRequester() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_REQUESTER_ACTION")).thenReturn(true);
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 4L));

        ApiException exception = assertApiException(
                () -> service.confirmResolution(TICKET_NO, new ConfirmResolutionCommand(4L)),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceStatus()).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(exception.resourceVersion()).isEqualTo(4L);
        verify(ticketMapper, never()).confirmResolution(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void confirmResolutionRejectsStaleVersion() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 5L));

        assertApiException(
                () -> service.confirmResolution(TICKET_NO, new ConfirmResolutionCommand(4L)),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never()).confirmResolution(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void confirmResolutionReportsConflictWithReloadedSnapshotWhenConditionalUpdateLoses() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 4L));
        when(ticketMapper.confirmResolution(TICKET_ID, 4L, REQUESTER_ID, NOW_UTC)).thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID))
                .thenReturn(conflictSnapshot(WAITING_FOR_CONFIRMATION, 5L));

        ApiException exception = assertApiException(
                () -> service.confirmResolution(TICKET_NO, new ConfirmResolutionCommand(4L)),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(5L);
        assertThat(exception.resourceStatus()).isEqualTo(WAITING_FOR_CONFIRMATION);
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void confirmResolutionCompletesTicketAndKeepsAssignee() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 4L));
        when(ticketMapper.confirmResolution(TICKET_ID, 4L, REQUESTER_ID, NOW_UTC)).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 6, 5L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.confirmResolution(TICKET_NO,
                new ConfirmResolutionCommand(4L));

        verify(ticketMapper).confirmResolution(TICKET_ID, 4L, REQUESTER_ID, NOW_UTC);
        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        TicketRecord record = records.getValue();
        assertThat(record.getRecordType()).isEqualTo("COMPLETION");
        assertThat(record.getSequenceNo()).isEqualTo(6);
        assertThat(record.getCompletionMethod()).isEqualTo("REQUESTER_CONFIRMED");
        assertThat(record.getContent()).as("确认动作没有正文").isNull();
        assertThat(record.getFromStatus()).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(record.getToStatus()).isEqualTo(COMPLETED);
        assertThat(record.getActorUserId()).isEqualTo(REQUESTER_ID);
        assertThat(record.getCreatedAt()).isEqualTo(NOW_UTC);

        assertThat(result.status()).isEqualTo(COMPLETED);
        assertThat(result.assignee()).as("终态保留最后负责人")
                .isEqualTo(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        assertThat(result.actionDeadlineAt()).as("终态期限失效").isNull();
        assertThat(result.version()).isEqualTo(5L);
        assertThat(result.actionTime()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    }

    @Test
    void confirmResolutionReturnsNullAssigneeWhenTicketHasNoAssignee() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, null, 4L));
        when(ticketMapper.confirmResolution(TICKET_ID, 4L, REQUESTER_ID, NOW_UTC)).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 6, 5L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.confirmResolution(TICKET_NO,
                new ConfirmResolutionCommand(4L));

        assertThat(result.status()).isEqualTo(COMPLETED);
        assertThat(result.assignee()).as("没有负责人时摘要为 null 而不是空对象").isNull();
    }

    // ---------- withdrawSupplementRequest ----------

    /** 权限闸门先于可见性：没有处理权限时不能借响应区分"工单是否存在"。 */
    @Test
    void withdrawSupplementRequestRejectsActorWithoutProcessAuthority() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_PROCESS")).thenReturn(false);

        assertApiException(
                () -> service.withdrawSupplementRequest(TICKET_NO,
                        new WithdrawSupplementRequestCommand(5L, "信息已补齐")),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verify(ticketMapper, never())
                .selectVisibleDetail(any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(ticketMapper, never())
                .withdrawSupplementRequest(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void withdrawSupplementRequestReportsNotFoundWhenTicketIsNotVisible() {
        stubProcessActor();

        assertApiException(
                () -> service.withdrawSupplementRequest(TICKET_NO,
                        new WithdrawSupplementRequestCommand(5L, "信息已补齐")),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verify(ticketMapper, never())
                .withdrawSupplementRequest(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void withdrawSupplementRequestReportsConflictWhenTicketIsNotWaitingForRequester() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        ApiException exception = assertApiException(
                () -> service.withdrawSupplementRequest(TICKET_NO,
                        new WithdrawSupplementRequestCommand(5L, "信息已补齐")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("冲突响应携带可见快照版本").isEqualTo(5L);
        assertThat(exception.resourceStatus()).as("冲突响应携带可见快照状态")
                .isEqualTo(PROCESSING);
        verify(ticketMapper, never())
                .withdrawSupplementRequest(anyLong(), anyLong(), anyLong(), any());
    }

    /** 有处理权限但不是当前负责人：按冲突返回，不用 403 暴露"谁是负责人"。 */
    @Test
    void withdrawSupplementRequestReportsConflictWhenActorIsNotTheCurrentAssignee() {
        stubProcessActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, OTHER_IT_USER_ID, 5L));

        ApiException exception = assertApiException(
                () -> service.withdrawSupplementRequest(TICKET_NO,
                        new WithdrawSupplementRequestCommand(5L, "信息已补齐")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(5L);
        assertThat(exception.resourceStatus()).isEqualTo(WAITING_FOR_REQUESTER);
        verify(ticketMapper, never())
                .withdrawSupplementRequest(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void withdrawSupplementRequestReportsConflictWhenTicketHasNoAssignee() {
        stubProcessActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, null, 5L));

        assertApiException(
                () -> service.withdrawSupplementRequest(TICKET_NO,
                        new WithdrawSupplementRequestCommand(5L, "信息已补齐")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never())
                .withdrawSupplementRequest(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void withdrawSupplementRequestReportsConflictWhenCommandVersionIsStale() {
        stubProcessActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));

        ApiException exception = assertApiException(
                () -> service.withdrawSupplementRequest(TICKET_NO,
                        new WithdrawSupplementRequestCommand(4L, "信息已补齐")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("冲突响应携带可见快照版本").isEqualTo(5L);
        assertThat(exception.resourceStatus()).isEqualTo(WAITING_FOR_REQUESTER);
        verify(ticketMapper, never())
                .withdrawSupplementRequest(anyLong(), anyLong(), anyLong(), any());
    }

    /** 校验顺序：状态/负责人/版本冲突先于原因长度，空白原因 + 过期版本必须得到 409。 */
    @Test
    void withdrawSupplementRequestReportsConflictBeforeValidatingBlankReason() {
        stubProcessActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));

        assertApiException(
                () -> service.withdrawSupplementRequest(TICKET_NO,
                        new WithdrawSupplementRequestCommand(4L, "   ")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never())
                .withdrawSupplementRequest(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void withdrawSupplementRequestRejectsBlankReasonWhenVersionMatches() {
        stubProcessActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));

        assertApiException(
                () -> service.withdrawSupplementRequest(TICKET_NO,
                        new WithdrawSupplementRequestCommand(5L, "   ")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never())
                .withdrawSupplementRequest(anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    /**
     * 非 HTTP 调用方给出 null 原因时与空白原因同一处理：400，不是 NPE。
     *
     * <p>Bean Validation 只在 Controller 那一层生效；服务被别的服务、脚本或测试直接调用时
     * `reason` 可以是 null。缺了 null 判断会先炸在 `isEmpty()` 上，表现成 500——
     * 与内容类动作（`content == null || content.isEmpty()`）的写法保持一致。</p>
     */
    @Test
    void withdrawSupplementRequestRejectsNullReasonWithoutThrowing() {
        stubProcessActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));

        assertApiException(
                () -> service.withdrawSupplementRequest(TICKET_NO,
                        new WithdrawSupplementRequestCommand(5L, null)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never())
                .withdrawSupplementRequest(anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    /** 原因上限 1000：超一个字符就归入 400，不能落库成被截断的说明。 */
    @Test
    void withdrawSupplementRequestRejectsReasonOverLimit() {
        stubProcessActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));

        assertApiException(
                () -> service.withdrawSupplementRequest(TICKET_NO,
                        new WithdrawSupplementRequestCommand(5L, "a".repeat(1001))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never())
                .withdrawSupplementRequest(anyLong(), anyLong(), anyLong(), any());
    }

    /** 边界：去除首尾空白后恰好 1000 个字符仍然合法。 */
    @Test
    void withdrawSupplementRequestAcceptsReasonAtExactLimit() {
        stubSuccessfulWithdraw();
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 7, 6L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        service.withdrawSupplementRequest(TICKET_NO,
                new WithdrawSupplementRequestCommand(5L, "  " + "a".repeat(1000) + "  "));

        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        assertThat(records.getValue().getReason()).as("恰好 1000 个字符的原因可以落库")
                .hasSize(1000);
    }

    /** 撤回成功：条件更新的四个入参、时间线记录与返回摘要三处必须一致，期限由 SQL 清空。 */
    @Test
    void withdrawSupplementRequestReturnsTicketToProcessingAndClearsDeadline() {
        stubSuccessfulWithdraw();
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 7, 6L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.withdrawSupplementRequest(TICKET_NO,
                new WithdrawSupplementRequestCommand(5L, "  提交人已补齐信息  "));

        verify(ticketMapper).withdrawSupplementRequest(TICKET_ID, 5L, IT_USER_ID, NOW_UTC);
        verify(ticketMapper, never())
                .reportUnresolved(anyLong(), anyLong(), anyLong(), any());

        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        TicketRecord record = records.getValue();
        assertThat(record.getRecordType()).isEqualTo("SUPPLEMENT_REQUEST_WITHDRAWN");
        assertThat(record.getSequenceNo()).as("序号取递增后的 recordSeq").isEqualTo(7);
        assertThat(record.getActorType()).isEqualTo("USER");
        assertThat(record.getActorUserId()).isEqualTo(IT_USER_ID);
        assertThat(record.getReason()).as("原因去除首尾空白后落库")
                .isEqualTo("提交人已补齐信息");
        assertThat(record.getContent()).as("撤回动作没有正文").isNull();
        assertThat(record.getFromStatus()).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(record.getToStatus()).isEqualTo(PROCESSING);
        assertThat(record.getCreatedAt()).as("落库时间按 UTC 毫秒截断").isEqualTo(NOW_UTC);

        assertThat(result.ticketNo()).isEqualTo(TICKET_NO);
        assertThat(result.status()).as("撤回后回到处理中").isEqualTo(PROCESSING);
        assertThat(result.assignee()).as("负责人不变，摘要取可见快照")
                .isEqualTo(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        assertThat(result.actionDeadlineAt()).as("原补充期限由条件更新一并清空").isNull();
        assertThat(result.version()).isEqualTo(6L);
        assertThat(result.actionTime()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    }

    @Test
    void withdrawSupplementRequestReportsConflictWithReloadedSnapshotWhenConditionalUpdateLoses() {
        stubProcessActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));
        when(ticketMapper.withdrawSupplementRequest(TICKET_ID, 5L, IT_USER_ID, NOW_UTC))
                .thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID))
                .thenReturn(conflictSnapshot(PROCESSING, 6L));

        ApiException exception = assertApiException(
                () -> service.withdrawSupplementRequest(TICKET_NO,
                        new WithdrawSupplementRequestCommand(5L, "信息已补齐")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("携带读回的最新版本").isEqualTo(6L);
        assertThat(exception.resourceStatus()).as("携带读回的最新状态").isEqualTo(PROCESSING);
        verify(ticketMapper, never()).selectById(TICKET_ID);
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void withdrawSupplementRequestFailsWhenConflictSnapshotCannotBeRead() {
        stubProcessActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));
        when(ticketMapper.withdrawSupplementRequest(TICKET_ID, 5L, IT_USER_ID, NOW_UTC))
                .thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.withdrawSupplementRequest(TICKET_NO,
                new WithdrawSupplementRequestCommand(5L, "信息已补齐")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("撤回补充请求冲突后无法读取工单快照");
    }

    @Test
    void withdrawSupplementRequestFailsWhenUpdatedTicketSnapshotIsMissing() {
        stubSuccessfulWithdraw();
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.withdrawSupplementRequest(TICKET_NO,
                new WithdrawSupplementRequestCommand(5L, "信息已补齐")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("撤回补充请求后无法读取工单快照");

        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void withdrawSupplementRequestFailsWhenUpdatedRecordSeqIsMissing() {
        stubSuccessfulWithdraw();
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, null, 6L));

        assertThatThrownBy(() -> service.withdrawSupplementRequest(TICKET_NO,
                new WithdrawSupplementRequestCommand(5L, "信息已补齐")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("撤回补充请求后无法读取工单快照");
    }

    @Test
    void withdrawSupplementRequestFailsWhenUpdatedVersionIsMissing() {
        stubSuccessfulWithdraw();
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 7, null));

        assertThatThrownBy(() -> service.withdrawSupplementRequest(TICKET_NO,
                new WithdrawSupplementRequestCommand(5L, "信息已补齐")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("撤回补充请求后无法读取工单快照");
    }

    // ---------- reportUnresolved ----------

    /**
     * 权限闸门先于提交人身份闸门：IT 负责人能看到工单、也确实是负责人，
     * 但缺 {@code TICKET_REQUESTER_ACTION} 时在读取可见性之前就该被 403 拦下。
     */
    @Test
    void reportUnresolvedChecksRequesterPermissionBeforeIdentity() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_REQUESTER_ACTION")).thenReturn(false);

        assertApiException(
                () -> service.reportUnresolved(TICKET_NO,
                        new ReportUnresolvedCommand(4L, "还没修好")),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verify(ticketMapper, never())
                .selectVisibleDetail(any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(ticketMapper, never()).reportUnresolved(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void reportUnresolvedReportsNotFoundWhenTicketIsNotVisible() {
        stubRequesterActor();

        assertApiException(
                () -> service.reportUnresolved(TICKET_NO,
                        new ReportUnresolvedCommand(4L, "还没修好")),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verify(ticketMapper, never()).reportUnresolved(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void reportUnresolvedReportsConflictWhenTicketIsNotWaitingForConfirmation() {
        stubRequesterActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 4L));

        ApiException exception = assertApiException(
                () -> service.reportUnresolved(TICKET_NO,
                        new ReportUnresolvedCommand(4L, "还没修好")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(4L);
        assertThat(exception.resourceStatus()).isEqualTo(PROCESSING);
        verify(ticketMapper, never()).reportUnresolved(anyLong(), anyLong(), anyLong(), any());
    }

    /** 有权限但不是提交人时按冲突处理，不用 403 暴露"谁是提交人"。 */
    @Test
    void reportUnresolvedReportsConflictWhenActorIsNotTheRequester() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_REQUESTER_ACTION")).thenReturn(true);
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 4L));

        ApiException exception = assertApiException(
                () -> service.reportUnresolved(TICKET_NO,
                        new ReportUnresolvedCommand(4L, "还没修好")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(4L);
        assertThat(exception.resourceStatus()).isEqualTo(WAITING_FOR_CONFIRMATION);
        verify(ticketMapper, never()).reportUnresolved(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void reportUnresolvedReportsConflictWhenCommandVersionIsStale() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 5L));

        ApiException exception = assertApiException(
                () -> service.reportUnresolved(TICKET_NO,
                        new ReportUnresolvedCommand(4L, "还没修好")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("冲突响应携带可见快照版本").isEqualTo(5L);
        assertThat(exception.resourceStatus()).isEqualTo(WAITING_FOR_CONFIRMATION);
        verify(ticketMapper, never()).reportUnresolved(anyLong(), anyLong(), anyLong(), any());
    }

    /** 校验顺序：状态/提交人/版本冲突先于原因长度，空白原因 + 过期版本必须得到 409。 */
    @Test
    void reportUnresolvedReportsConflictBeforeValidatingBlankReason() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 5L));

        assertApiException(
                () -> service.reportUnresolved(TICKET_NO,
                        new ReportUnresolvedCommand(4L, "   ")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never()).reportUnresolved(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void reportUnresolvedRejectsBlankReasonWhenVersionMatches() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 4L));

        assertApiException(
                () -> service.reportUnresolved(TICKET_NO,
                        new ReportUnresolvedCommand(4L, "   ")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).reportUnresolved(anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    /** 非 HTTP 调用方给出 null 原因同样归入 400 校验分支，不能因为 null 变成 500。 */
    @Test
    void reportUnresolvedRejectsNullReasonWithoutThrowing() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 4L));

        assertApiException(
                () -> service.reportUnresolved(TICKET_NO,
                        new ReportUnresolvedCommand(4L, null)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).reportUnresolved(anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void reportUnresolvedRejectsReasonOverLimit() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 4L));

        assertApiException(
                () -> service.reportUnresolved(TICKET_NO,
                        new ReportUnresolvedCommand(4L, "a".repeat(1001))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).reportUnresolved(anyLong(), anyLong(), anyLong(), any());
    }

    /** 反馈未解决成功：回到处理中、期限清空、负责人保留，时间线写 {@code UNSATISFIED_FEEDBACK}。 */
    @Test
    void reportUnresolvedReturnsTicketToProcessingAndKeepsAssignee() {
        stubSuccessfulReport();
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 6, 5L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.reportUnresolved(TICKET_NO,
                new ReportUnresolvedCommand(4L, "  问题又出现了  "));

        verify(ticketMapper).reportUnresolved(TICKET_ID, 4L, REQUESTER_ID, NOW_UTC);
        verify(ticketMapper, never())
                .withdrawSupplementRequest(anyLong(), anyLong(), anyLong(), any());

        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        TicketRecord record = records.getValue();
        assertThat(record.getRecordType()).isEqualTo("UNSATISFIED_FEEDBACK");
        assertThat(record.getSequenceNo()).as("序号取递增后的 recordSeq").isEqualTo(6);
        assertThat(record.getActorType()).isEqualTo("USER");
        assertThat(record.getActorUserId()).as("动作由提交人执行").isEqualTo(REQUESTER_ID);
        assertThat(record.getReason()).as("原因去除首尾空白后落库").isEqualTo("问题又出现了");
        assertThat(record.getContent()).as("未解决反馈没有正文").isNull();
        assertThat(record.getFromStatus()).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(record.getToStatus()).isEqualTo(PROCESSING);
        assertThat(record.getFromAssigneeId()).as("退回不等于换人").isNull();
        assertThat(record.getToAssigneeId()).as("退回不等于换人").isNull();
        assertThat(record.getCreatedAt()).isEqualTo(NOW_UTC);

        assertThat(result.ticketNo()).isEqualTo(TICKET_NO);
        assertThat(result.status()).as("反馈后回到处理中").isEqualTo(PROCESSING);
        assertThat(result.assignee()).as("原负责人保留")
                .isEqualTo(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        assertThat(result.actionDeadlineAt()).as("原确认期限由条件更新一并清空").isNull();
        assertThat(result.version()).isEqualTo(5L);
        assertThat(result.actionTime()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    }

    /** 没有负责人时摘要为 null 而不是空对象：反馈路径与确认路径同一口径。 */
    @Test
    void reportUnresolvedReturnsNullAssigneeWhenTicketHasNoAssignee() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, null, 4L));
        when(ticketMapper.reportUnresolved(TICKET_ID, 4L, REQUESTER_ID, NOW_UTC)).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 6, 5L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.reportUnresolved(TICKET_NO,
                new ReportUnresolvedCommand(4L, "还没修好"));

        assertThat(result.status()).isEqualTo(PROCESSING);
        assertThat(result.assignee()).isNull();
    }

    @Test
    void reportUnresolvedReportsConflictWithReloadedSnapshotWhenConditionalUpdateLoses() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 4L));
        when(ticketMapper.reportUnresolved(TICKET_ID, 4L, REQUESTER_ID, NOW_UTC)).thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID))
                .thenReturn(conflictSnapshot(COMPLETED, 5L));

        ApiException exception = assertApiException(
                () -> service.reportUnresolved(TICKET_NO,
                        new ReportUnresolvedCommand(4L, "还没修好")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("携带读回的最新版本").isEqualTo(5L);
        assertThat(exception.resourceStatus()).as("携带读回的最新状态").isEqualTo(COMPLETED);
        verify(ticketMapper, never()).selectById(TICKET_ID);
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void reportUnresolvedFailsWhenConflictSnapshotCannotBeRead() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 4L));
        when(ticketMapper.reportUnresolved(TICKET_ID, 4L, REQUESTER_ID, NOW_UTC)).thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.reportUnresolved(TICKET_NO,
                new ReportUnresolvedCommand(4L, "还没修好")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("反馈未解决冲突后无法读取工单快照");
    }

    @Test
    void reportUnresolvedFailsWhenUpdatedTicketSnapshotIsMissing() {
        stubSuccessfulReport();
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.reportUnresolved(TICKET_NO,
                new ReportUnresolvedCommand(4L, "还没修好")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("反馈未解决后无法读取工单快照");

        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void reportUnresolvedFailsWhenUpdatedRecordSeqIsMissing() {
        stubSuccessfulReport();
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, null, 5L));

        assertThatThrownBy(() -> service.reportUnresolved(TICKET_NO,
                new ReportUnresolvedCommand(4L, "还没修好")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("反馈未解决后无法读取工单快照");
    }

    @Test
    void reportUnresolvedFailsWhenUpdatedVersionIsMissing() {
        stubSuccessfulReport();
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 6, null));

        assertThatThrownBy(() -> service.reportUnresolved(TICKET_NO,
                new ReportUnresolvedCommand(4L, "还没修好")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("反馈未解决后无法读取工单快照");
    }

    // ---------- TicketProperties ----------

    @Test
    void ticketPropertiesDefaultToSevenDaysWhenUnset() {
        assertThat(new TicketProperties(null).confirmationWindow())
                .as("未配置时使用已确认的 7×24 小时默认值")
                .isEqualTo(Duration.ofDays(7));
    }

    @Test
    void ticketPropertiesRejectWindowBelowOneMinute() {
        assertThatThrownBy(() -> new TicketProperties(Duration.ofSeconds(30)))
                .as("小于 1 分钟的确认期限会让员工无法在期限内操作")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirmation-window");
    }

    // ---------- 事务边界 ----------

    @Test
    void actionMethodsDeclareTransactionBoundaries() throws NoSuchMethodException {
        assertThat(TicketServiceImpl.class
                .getMethod("claim", String.class, ClaimTicketCommand.class)
                .isAnnotationPresent(Transactional.class))
                .as("claim 由声明式事务包住条件更新与时间线写入").isTrue();
        assertThat(TicketServiceImpl.class
                .getMethod("addProcessingRecord", String.class, AddProcessingRecordCommand.class)
                .isAnnotationPresent(Transactional.class))
                .as("addProcessingRecord 由声明式事务包住条件更新与时间线写入").isTrue();
        assertThat(TicketServiceImpl.class
                .getMethod("submitResolution", String.class, SubmitResolutionCommand.class)
                .isAnnotationPresent(Transactional.class))
                .as("submitResolution 由声明式事务包住条件更新与时间线写入").isTrue();
        assertThat(TicketServiceImpl.class
                .getMethod("confirmResolution", String.class, ConfirmResolutionCommand.class)
                .isAnnotationPresent(Transactional.class))
                .as("confirmResolution 由声明式事务包住条件更新与时间线写入").isTrue();
        assertThat(TicketServiceImpl.class
                .getMethod("create", CreateTicketCommand.class)
                .isAnnotationPresent(Transactional.class))
                .as("create 用 REQUIRES_NEW 的 TransactionTemplate 自行管理事务边界").isFalse();
    }

    /** 片 A 的两个退回动作同样必须由声明式事务包住条件更新与时间线写入。 */
    @Test
    void returnActionMethodsDeclareTransactionBoundaries() throws NoSuchMethodException {
        assertThat(TicketServiceImpl.class
                .getMethod("withdrawSupplementRequest", String.class,
                        WithdrawSupplementRequestCommand.class)
                .isAnnotationPresent(Transactional.class))
                .as("withdrawSupplementRequest 由声明式事务包住条件更新与时间线写入").isTrue();
        assertThat(TicketServiceImpl.class
                .getMethod("reportUnresolved", String.class, ReportUnresolvedCommand.class)
                .isAnnotationPresent(Transactional.class))
                .as("reportUnresolved 由声明式事务包住条件更新与时间线写入").isTrue();
    }

    // ---------- 辅助 ----------

    private TicketServiceImpl service(Clock clock, TicketProperties properties) {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new TicketServiceImpl(
                clock,
                ticketMapper,
                ticketDailySequenceMapper,
                ticketRecordMapper,
                currentRequesterPort,
                categoryAvailabilityPort,
                ticketReadPermissionPort,
                transactionManager,
                ticketClaimPort,
                ticketParticipantMapper,
                properties);
    }

    private static CreateTicketCommand createCommand(String submissionKey) {
        return new CreateTicketCommand(
                submissionKey, "  打印机无法连接  ", " 三楼打印机离线 ", CATEGORY_ID, HIGH);
    }

    /** 首次创建路径：分类启用、序号可用、插入成功并回填工单 ID。 */
    private void stubFirstCreation(Long sequence) {
        when(currentRequesterPort.currentUserId()).thenReturn(REQUESTER_ID);
        when(ticketMapper.selectCreationByRequesterAndSubmissionKey(REQUESTER_ID, SUBMISSION_KEY))
                .thenReturn(null);
        when(categoryAvailabilityPort.isEnabled(CATEGORY_ID)).thenReturn(true);
        when(ticketDailySequenceMapper.selectCurrentForUpdate(any(LocalDate.class)))
                .thenReturn(sequence);
        when(ticketMapper.insert(any(Ticket.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, Ticket.class).setId(TICKET_ID);
            return 1;
        });
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);
    }

    private static Ticket existingTicket(
            String ticketNo, LocalDateTime createdAt, String status, long version) {
        Ticket ticket = new Ticket();
        ticket.setId(TICKET_ID);
        ticket.setTicketNo(ticketNo);
        ticket.setStatus(status);
        ticket.setVersion(version);
        ticket.setCreatedAt(createdAt);
        return ticket;
    }

    private static Ticket updatedTicket(String ticketNo, Integer recordSeq, Long version) {
        Ticket ticket = new Ticket();
        ticket.setId(TICKET_ID);
        ticket.setTicketNo(ticketNo);
        ticket.setStatus(PROCESSING);
        ticket.setAssigneeId(IT_USER_ID);
        ticket.setRecordSeq(recordSeq);
        ticket.setVersion(version);
        return ticket;
    }

    private static Ticket conflictSnapshot(String status, Long version) {
        Ticket ticket = new Ticket();
        ticket.setId(TICKET_ID);
        ticket.setStatus(status);
        ticket.setVersion(version);
        return ticket;
    }

    private static TicketUserSummaryResult eligibleClaimant() {
        return new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME);
    }

    private static TicketDetailRow detailRow(String status, Long assigneeId, long version) {
        return detailRow(status, assigneeId, version, REQUESTER_ID);
    }

    private static TicketDetailRow detailRow(
            String status, Long assigneeId, long version, long requesterId) {
        TicketDetailRow row = new TicketDetailRow();
        row.setId(TICKET_ID);
        row.setTicketNo(TICKET_NO);
        row.setTitle("打印机无法连接");
        row.setCategoryId(CATEGORY_ID);
        row.setCategoryName("办公设备");
        row.setPriority(HIGH);
        row.setStatus(status);
        row.setRequesterId(requesterId);
        row.setRequesterDisplayName("演示员工");
        row.setAssigneeId(assigneeId);
        row.setAssigneeDisplayName(assigneeId == null ? null : IT_DISPLAY_NAME);
        row.setVersion(version);
        row.setCreatedAt(NOW_UTC);
        row.setUpdatedAt(NOW_UTC);
        return row;
    }

    private void stubEligibleClaimant() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_CLAIM")).thenReturn(true);
        when(ticketClaimPort.lockEligibleClaimant(IT_USER_ID)).thenReturn(eligibleClaimant());
    }

    private void stubSuccessfulConditionalClaim() {
        stubEligibleClaimant();
        stubVisible(detailRow(PENDING, null, 0L));
        when(ticketMapper.claimPending(TICKET_ID, 0L, IT_USER_ID, NOW_UTC)).thenReturn(1);
    }

    private void stubProcessActor() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_PROCESS")).thenReturn(true);
    }

    private void stubSubmittableTicket(long version, LocalDateTime deadline) {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, version));
        when(ticketMapper.submitResolution(TICKET_ID, version, IT_USER_ID, deadline, NOW_UTC))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 5, 4L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);
    }

    private void stubRequesterActor() {
        when(currentRequesterPort.currentUserId()).thenReturn(REQUESTER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_REQUESTER_ACTION")).thenReturn(true);
    }

    /** 撤回路径的公共前置：有处理权限、本人是「待补充」的负责人、版本 5。 */
    private void stubSuccessfulWithdraw() {
        stubProcessActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L));
        when(ticketMapper.withdrawSupplementRequest(TICKET_ID, 5L, IT_USER_ID, NOW_UTC))
                .thenReturn(1);
    }

    /** 未解决反馈路径的公共前置：有提交人权限、本人是「待确认」的提交人、版本 4。 */
    private void stubSuccessfulReport() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 4L));
        when(ticketMapper.reportUnresolved(TICKET_ID, 4L, REQUESTER_ID, NOW_UTC))
                .thenReturn(1);
    }

    private void stubVisible(TicketDetailRow row) {
        when(ticketMapper.selectVisibleDetail(any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(row);
    }

    private static ApiException assertApiException(
            ThrowingCallable action, HttpStatus status, String code) {
        Throwable thrown = catchThrowable(action);
        assertThat(thrown).as("应抛出 ApiException，实际为 %s", thrown).isInstanceOf(ApiException.class);
        ApiException exception = (ApiException) thrown;
        assertThat(exception.status()).as("HTTP 状态").isEqualTo(status);
        assertThat(exception.code()).as("稳定错误编码").isEqualTo(code);
        return exception;
    }
}
