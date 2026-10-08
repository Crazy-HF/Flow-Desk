package com.flowdesk.ticket.application.service.impl;

import com.flowdesk.common.exception.ApiException;
import com.flowdesk.ticket.application.command.AddProcessingRecordCommand;
import com.flowdesk.ticket.application.command.ChangeCategoryCommand;
import com.flowdesk.ticket.application.command.ChangePriorityCommand;
import com.flowdesk.ticket.application.command.CancelTicketCommand;
import com.flowdesk.ticket.application.command.ClaimTicketCommand;
import com.flowdesk.ticket.application.command.CloseTicketCommand;
import com.flowdesk.ticket.application.command.ConfirmResolutionCommand;
import com.flowdesk.ticket.application.command.CreateTicketCommand;
import com.flowdesk.ticket.application.command.ReportUnresolvedCommand;
import com.flowdesk.ticket.application.command.RequestSupplementCommand;
import com.flowdesk.ticket.application.command.SubmitResolutionCommand;
import com.flowdesk.ticket.application.command.SupplementCommand;
import com.flowdesk.ticket.application.command.TransferCommand;
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
import com.flowdesk.ticket.infrastructure.persistence.TicketDuplicateTargetRow;
import com.flowdesk.ticket.mapper.TicketDailySequenceMapper;
import com.flowdesk.ticket.mapper.TicketMapper;
import com.flowdesk.ticket.mapper.TicketParticipantMapper;
import com.flowdesk.ticket.mapper.TicketRecordMapper;
import com.flowdesk.ticket.mapper.TicketRelationMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
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
 *
 * <p>片 D 的结束路径（{@code close} 与 {@code cancel}）沿用同一套顺序守卫，差别在入口条件：
 * 关闭要求 {@code TICKET_PROCESS} <b>与</b> {@code TICKET_CLOSE} 同时成立、且工单处于「处理中」；
 * 撤销只要求 {@code TICKET_REQUESTER_ACTION}、但四种非终态都放行。两者都写入终态，
 * 因此用例额外钉住"终态字段彼此可区分"：关闭写 {@code close_method}/{@code close_reason}，
 * 撤销两者都为空。</p>
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
    private static final long NEW_IT_USER_ID = 11L;
    private static final long TICKET_ID = 1001L;
    private static final long CATEGORY_ID = 3L;
    private static final long NEW_CATEGORY_ID = 4L;

    private static final String TICKET_NO = "FD-20261006-001";
    private static final String IT_DISPLAY_NAME = "演示 IT 支持人员";
    private static final String OTHER_IT_DISPLAY_NAME = "演示 IT 二号";
    private static final String NEW_IT_DISPLAY_NAME = "演示 IT 三号";
    private static final String PENDING = "PENDING";
    private static final String PROCESSING = "PROCESSING";
    private static final String WAITING_FOR_REQUESTER = "WAITING_FOR_REQUESTER";
    private static final String WAITING_FOR_CONFIRMATION = "WAITING_FOR_CONFIRMATION";
    private static final String COMPLETED = "COMPLETED";
    private static final String CANCELED = "CANCELED";
    private static final String CLOSED = "CLOSED";
    private static final String HIGH = "HIGH";
    private static final String LOW = "LOW";

    /** 「待补充」工单的有效期限，用于断言调整与转交都不会让它失效。 */
    private static final LocalDateTime SUPPLEMENT_DEADLINE =
            LocalDateTime.of(2026, 10, 13, 8, 15, 30);

    private static final String SUBMISSION_KEY = "3f1c9f4e-2a6b-4b7c-8d9e-0a1b2c3d4e5f";
    private static final TicketProperties DEFAULT_PROPERTIES =
            new TicketProperties(Duration.ofDays(7), Duration.ofDays(7));

    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private TicketDailySequenceMapper ticketDailySequenceMapper;
    @Mock
    private TicketRecordMapper ticketRecordMapper;
    @Mock
    private TicketParticipantMapper ticketParticipantMapper;

    /** 片 D 的「重复工单」关闭要写 ticket_relation，构造签名随之多一个参数。 */
    @Mock
    private TicketRelationMapper ticketRelationMapper;
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

        TicketActionResult result = service(CLOCK,
                new TicketProperties(Duration.ofHours(1), null))
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

    // ---------- requestSupplement ----------

    /**
     * 权限闸门先于可见性：本人确实是"处理中"的负责人，但缺 {@code TICKET_PROCESS} 时，
     * 在读取工单之前就该被 403 拦下，不能靠"是不是负责人"推断能不能处理。
     */
    @Test
    void requestSupplementChecksProcessPermissionBeforeVisibility() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_PROCESS")).thenReturn(false);

        assertApiException(
                () -> service.requestSupplement(TICKET_NO,
                        new RequestSupplementCommand(3L, "请补充打印机型号")),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verify(ticketMapper, never()).selectVisibleDetail(
                any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    void requestSupplementReportsNotFoundWhenTicketIsNotVisible() {
        stubProcessActor();
        when(ticketMapper.selectVisibleDetail(
                any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(null);

        assertApiException(
                () -> service.requestSupplement(TICKET_NO,
                        new RequestSupplementCommand(3L, "请补充打印机型号")),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verify(ticketMapper, never()).requestSupplement(
                anyLong(), anyLong(), anyLong(), any(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void requestSupplementReportsConflictWhenTicketIsNotProcessing() {
        stubProcessActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 3L));

        ApiException exception = assertApiException(
                () -> service.requestSupplement(TICKET_NO,
                        new RequestSupplementCommand(3L, "请补充打印机型号")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceStatus()).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(exception.resourceVersion()).as("冲突快照取可见行").isEqualTo(3L);
    }

    /** 能看到"处理中"的工单但不是负责人：同样按冲突返回，不回显"你不是负责人"。 */
    @Test
    void requestSupplementReportsConflictWhenActorIsNotTheCurrentAssignee() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID + 1, 3L));

        assertApiException(
                () -> service.requestSupplement(TICKET_NO,
                        new RequestSupplementCommand(3L, "请补充打印机型号")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never()).requestSupplement(
                anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void requestSupplementReportsConflictWhenTicketHasNoAssignee() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, null, 3L));

        assertApiException(
                () -> service.requestSupplement(TICKET_NO,
                        new RequestSupplementCommand(3L, "请补充打印机型号")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");
    }

    @Test
    void requestSupplementReportsConflictWhenCommandVersionIsStale() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        ApiException exception = assertApiException(
                () -> service.requestSupplement(TICKET_NO,
                        new RequestSupplementCommand(3L, "请补充打印机型号")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("响应携带当前版本供前端刷新").isEqualTo(5L);
        verify(ticketMapper, never()).requestSupplement(
                anyLong(), anyLong(), anyLong(), any(), any());
    }

    /**
     * 顺序陷阱：状态已变但请求正文也是空白时，必须回报 409 而不是 400——
     * 否则用户会以为"把内容填上就能提交"，而真正的问题是工单已被别人推进。
     */
    @Test
    void requestSupplementReportsConflictBeforeValidatingBlankContent() {
        stubProcessActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 3L));

        assertApiException(
                () -> service.requestSupplement(TICKET_NO,
                        new RequestSupplementCommand(3L, "   ")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");
    }

    @Test
    void requestSupplementRejectsBlankContentWhenVersionMatches() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertApiException(
                () -> service.requestSupplement(TICKET_NO,
                        new RequestSupplementCommand(3L, "   ")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).requestSupplement(
                anyLong(), anyLong(), anyLong(), any(), any());
    }

    /** 非 HTTP 调用方（脚本、其它服务）不走 Bean Validation，null 必须在服务内兜住而不是 NPE。 */
    @Test
    void requestSupplementRejectsNullContentWithoutThrowing() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertApiException(
                () -> service.requestSupplement(TICKET_NO,
                        new RequestSupplementCommand(3L, null)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void requestSupplementRejectsContentOverLimit() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertApiException(
                () -> service.requestSupplement(TICKET_NO,
                        new RequestSupplementCommand(3L, "补".repeat(10001))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void requestSupplementAcceptsContentAtExactLimit() {
        stubSuccessfulRequestSupplement(3L, Duration.ofDays(7));
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 5, 4L));

        TicketActionResult result = service.requestSupplement(TICKET_NO,
                new RequestSupplementCommand(3L, "补".repeat(10000)));

        assertThat(result.status()).isEqualTo(WAITING_FOR_REQUESTER);
    }

    /** 期限取配置而不是写死 7 天：窗口配成 1 小时时，落库期限必须跟着变。 */
    @Test
    void requestSupplementUsesConfiguredSupplementWindow() {
        LocalDateTime deadline = NOW_UTC.plus(Duration.ofHours(1));
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));
        when(ticketMapper.requestSupplement(TICKET_ID, 3L, IT_USER_ID, deadline, NOW_UTC))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 5, 4L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service(CLOCK,
                new TicketProperties(null, Duration.ofHours(1)))
                .requestSupplement(TICKET_NO,
                        new RequestSupplementCommand(3L, "请补充打印机型号"));

        verify(ticketMapper).requestSupplement(TICKET_ID, 3L, IT_USER_ID, deadline, NOW_UTC);
        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        assertThat(records.getValue().getDeadlineAt())
                .as("时间线记录与条件更新使用同一期限").isEqualTo(deadline);
        assertThat(result.actionDeadlineAt())
                .as("返回结果的期限按 UTC 偏移").isEqualTo(deadline.atOffset(ZoneOffset.UTC));
    }

    @Test
    void requestSupplementMovesTicketToWaitingForRequesterAndKeepsAssignee() {
        stubSuccessfulRequestSupplement(3L, Duration.ofDays(7));
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 5, 4L));

        TicketActionResult result = service.requestSupplement(TICKET_NO,
                new RequestSupplementCommand(3L, "  请补充打印机型号与错误截图  "));

        LocalDateTime deadline = NOW_UTC.plus(Duration.ofDays(7));
        verify(ticketMapper).requestSupplement(TICKET_ID, 3L, IT_USER_ID, deadline, NOW_UTC);

        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        TicketRecord record = records.getValue();
        assertThat(record.getRecordType()).isEqualTo("SUPPLEMENT_REQUEST");
        assertThat(record.getSequenceNo()).as("序号取递增后的 recordSeq").isEqualTo(5);
        assertThat(record.getActorType()).isEqualTo("USER");
        assertThat(record.getActorUserId()).isEqualTo(IT_USER_ID);
        assertThat(record.getContent()).as("需要补充的内容去除首尾空白后落库")
                .isEqualTo("请补充打印机型号与错误截图");
        assertThat(record.getReason()).as("请求补充不是原因类动作").isNull();
        assertThat(record.getFromStatus()).isEqualTo(PROCESSING);
        assertThat(record.getToStatus()).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(record.getDeadlineAt()).as("记录与工单写入同一补充期限").isEqualTo(deadline);
        assertThat(record.getCreatedAt()).as("落库时间按 UTC 毫秒截断").isEqualTo(NOW_UTC);

        assertThat(result.ticketNo()).isEqualTo(TICKET_NO);
        assertThat(result.status()).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(result.assignee()).as("请求补充不换人，负责人仍在")
                .isEqualTo(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        assertThat(result.actionDeadlineAt()).isEqualTo(deadline.atOffset(ZoneOffset.UTC));
        assertThat(result.version()).isEqualTo(4L);
        assertThat(result.actionTime()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    }

    @Test
    void requestSupplementReportsConflictWithReloadedSnapshotWhenConditionalUpdateLoses() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));
        when(ticketMapper.requestSupplement(
                anyLong(), anyLong(), anyLong(), any(), any())).thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID))
                .thenReturn(conflictSnapshot(WAITING_FOR_REQUESTER, 4L));

        ApiException exception = assertApiException(
                () -> service.requestSupplement(TICKET_NO,
                        new RequestSupplementCommand(3L, "请补充打印机型号")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("携带读回的最新版本").isEqualTo(4L);
        assertThat(exception.resourceStatus()).as("携带读回的最新状态")
                .isEqualTo(WAITING_FOR_REQUESTER);
        verify(ticketMapper, never()).selectById(TICKET_ID);
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void requestSupplementFailsWhenConflictSnapshotCannotBeRead() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));
        when(ticketMapper.requestSupplement(
                anyLong(), anyLong(), anyLong(), any(), any())).thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.requestSupplement(TICKET_NO,
                new RequestSupplementCommand(3L, "请补充打印机型号")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("请求补充冲突后无法读取工单快照");
    }

    @Test
    void requestSupplementFailsWhenUpdatedRecordSeqIsMissing() {
        stubSuccessfulRequestSupplement(3L, Duration.ofDays(7));
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, null, 4L));

        assertThatThrownBy(() -> service.requestSupplement(TICKET_NO,
                new RequestSupplementCommand(3L, "请补充打印机型号")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("请求补充后无法读取工单快照");

        verifyNoInteractions(ticketRecordMapper);
    }

    // ---------- changeCategory ----------

    /**
     * 调整分类的第一道门禁是 {@code TICKET_PROCESS}，且必须在读可见性之前——
     * 否则无权限的调用者能借响应差异区分"工单是否存在"。
     */
    @Test
    void changeCategoryRejectsActorWithoutProcessAuthority() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_PROCESS")).thenReturn(false);

        assertApiException(
                () -> service.changeCategory(TICKET_NO,
                        new ChangeCategoryCommand(5L, NEW_CATEGORY_ID, "分类选错了")),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verify(ticketMapper, never())
                .selectVisibleDetail(any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(ticketMapper, never())
                .changeCategory(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    /**
     * 编号不存在或调用者不可见时必须是 {@code 404/TICKET_NOT_FOUND}。
     *
     * <p>本用例是片 C 抓到的真实缺陷的回归守卫：{@code changeCategory} 一开始漏了
     * {@code visible == null} 判断，同文件其余 10 处 {@code selectVisibleDetail} 都有。
     * 缺这一步会让 {@code visible.getStatus()} 直接 NPE，HTTP 上表现为
     * {@code 500/INTERNAL_ERROR}——与"不可见与不存在统一 404"的约定冲突。
     * 判空顺序同样重要：它必须早于状态/负责人/版本判定，否则 404 会先变成 409。</p>
     */
    @Test
    void changeCategoryReportsNotFoundWhenTicketIsNotVisible() {
        stubProcessActor();

        assertApiException(
                () -> service.changeCategory(TICKET_NO,
                        new ChangeCategoryCommand(5L, NEW_CATEGORY_ID, "分类选错了")),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verify(ticketMapper, never())
                .changeCategory(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    /** 只允许「处理中」与「待补充」：其他状态按冲突返回，并带上可见快照。 */
    @Test
    void changeCategoryReportsConflictWhenTicketIsNotAdjustable() {
        stubProcessActor();
        stubVisible(detailRow(PENDING, null, 5L));

        ApiException exception = assertApiException(
                () -> service.changeCategory(TICKET_NO,
                        new ChangeCategoryCommand(5L, NEW_CATEGORY_ID, "分类选错了")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("冲突响应携带可见快照版本").isEqualTo(5L);
        assertThat(exception.resourceStatus()).as("冲突响应携带可见快照状态").isEqualTo(PENDING);
        verify(ticketMapper, never())
                .changeCategory(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    /** 有处理权限但不是当前负责人：按冲突返回，不用 403 暴露"谁是负责人"。 */
    @Test
    void changeCategoryReportsConflictWhenActorIsNotTheCurrentAssignee() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, OTHER_IT_USER_ID, 5L));

        ApiException exception = assertApiException(
                () -> service.changeCategory(TICKET_NO,
                        new ChangeCategoryCommand(5L, NEW_CATEGORY_ID, "分类选错了")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(5L);
        assertThat(exception.resourceStatus()).isEqualTo(PROCESSING);
        verify(ticketMapper, never())
                .changeCategory(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void changeCategoryReportsConflictWhenTicketHasNoAssignee() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, null, 5L));

        assertApiException(
                () -> service.changeCategory(TICKET_NO,
                        new ChangeCategoryCommand(5L, NEW_CATEGORY_ID, "分类选错了")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never())
                .changeCategory(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void changeCategoryReportsConflictWhenCommandVersionIsStale() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        ApiException exception = assertApiException(
                () -> service.changeCategory(TICKET_NO,
                        new ChangeCategoryCommand(4L, NEW_CATEGORY_ID, "分类选错了")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("冲突响应携带可见快照版本").isEqualTo(5L);
        assertThat(exception.resourceStatus()).isEqualTo(PROCESSING);
        verify(ticketMapper, never())
                .changeCategory(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    /** 校验顺序：状态/负责人/版本冲突先于原因长度与分类有效性。 */
    @Test
    void changeCategoryReportsConflictBeforeValidatingReasonAndCategory() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.changeCategory(TICKET_NO,
                        new ChangeCategoryCommand(4L, NEW_CATEGORY_ID, "   ")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verifyNoInteractions(categoryAvailabilityPort);
        verify(ticketMapper, never())
                .changeCategory(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void changeCategoryRejectsBlankReasonWhenVersionMatches() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.changeCategory(TICKET_NO,
                        new ChangeCategoryCommand(5L, NEW_CATEGORY_ID, "   ")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(categoryAvailabilityPort);
        verify(ticketMapper, never())
                .changeCategory(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    /** 非 HTTP 调用方给出 null 原因时与空白原因同一处理：400，不是 NPE。 */
    @Test
    void changeCategoryRejectsNullReasonWithoutThrowing() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.changeCategory(TICKET_NO,
                        new ChangeCategoryCommand(5L, NEW_CATEGORY_ID, null)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never())
                .changeCategory(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    /** 说明上限 1000：超一个字符就归入 400，不能落库成被截断的说明。 */
    @Test
    void changeCategoryRejectsReasonOverLimit() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.changeCategory(TICKET_NO,
                        new ChangeCategoryCommand(5L, NEW_CATEGORY_ID, "a".repeat(1001))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never())
                .changeCategory(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    /** 目标分类必须存在且启用：停用分类不能再被选为当前分类。 */
    @Test
    void changeCategoryRejectsDisabledCategory() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(categoryAvailabilityPort.isEnabled(NEW_CATEGORY_ID)).thenReturn(false);

        assertApiException(
                () -> service.changeCategory(TICKET_NO,
                        new ChangeCategoryCommand(5L, NEW_CATEGORY_ID, "分类选错了")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never())
                .changeCategory(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    /** 成功路径：条件更新的五个入参、时间线记录与返回摘要三处必须一致。 */
    @Test
    void changeCategoryReplacesCategoryAndKeepsStatusAndAssignee() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(categoryAvailabilityPort.isEnabled(NEW_CATEGORY_ID)).thenReturn(true);
        when(ticketMapper.changeCategory(TICKET_ID, 5L, IT_USER_ID, NEW_CATEGORY_ID, NOW_UTC))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 6, 6L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.changeCategory(TICKET_NO,
                new ChangeCategoryCommand(5L, NEW_CATEGORY_ID, "  分类选错了  "));

        verify(ticketMapper).changeCategory(TICKET_ID, 5L, IT_USER_ID, NEW_CATEGORY_ID, NOW_UTC);
        verify(ticketMapper, never())
                .changePriority(anyLong(), anyLong(), anyLong(), any(), any());

        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        TicketRecord record = records.getValue();
        assertThat(record.getRecordType()).isEqualTo("CATEGORY_CHANGE");
        assertThat(record.getSequenceNo()).as("序号取递增后的 recordSeq").isEqualTo(6);
        assertThat(record.getActorType()).isEqualTo("USER");
        assertThat(record.getActorUserId()).isEqualTo(IT_USER_ID);
        assertThat(record.getReason()).as("说明去除首尾空白后落库").isEqualTo("分类选错了");
        assertThat(record.getContent()).as("调整动作没有正文").isNull();
        assertThat(record.getFromCategoryId()).isEqualTo(CATEGORY_ID);
        assertThat(record.getToCategoryId()).isEqualTo(NEW_CATEGORY_ID);
        assertThat(record.getFromPriority()).as("分类调整不写优先级快照").isNull();
        assertThat(record.getToPriority()).isNull();
        assertThat(record.getFromStatus()).as("状态没变，两侧写同一个状态")
                .isEqualTo(PROCESSING);
        assertThat(record.getToStatus()).isEqualTo(PROCESSING);
        assertThat(record.getCreatedAt()).as("落库时间按 UTC 毫秒截断").isEqualTo(NOW_UTC);

        assertThat(result.ticketNo()).isEqualTo(TICKET_NO);
        assertThat(result.status()).as("状态不变").isEqualTo(PROCESSING);
        assertThat(result.assignee()).as("负责人不变，摘要取可见快照")
                .isEqualTo(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        assertThat(result.actionDeadlineAt()).as("处理中本来就没有期限").isNull();
        assertThat(result.version()).isEqualTo(6L);
        assertThat(result.actionTime()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    }

    /** 「待补充」也能调整分类：状态与期限都不动，期限原样回给调用方。 */
    @Test
    void changeCategoryKeepsSupplementDeadlineWhileWaitingForRequester() {
        stubProcessActor();
        TicketDetailRow row = detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L);
        row.setActionDeadlineAt(SUPPLEMENT_DEADLINE);
        stubVisible(row);
        when(categoryAvailabilityPort.isEnabled(NEW_CATEGORY_ID)).thenReturn(true);
        when(ticketMapper.changeCategory(TICKET_ID, 5L, IT_USER_ID, NEW_CATEGORY_ID, NOW_UTC))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 6, 6L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.changeCategory(TICKET_NO,
                new ChangeCategoryCommand(5L, NEW_CATEGORY_ID, "分类选错了"));

        assertThat(result.status()).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(result.actionDeadlineAt()).as("调整不碰期限，待补充的截止时间仍然有效")
                .isEqualTo(SUPPLEMENT_DEADLINE.atOffset(ZoneOffset.UTC));
    }

    /**
     * 调整成同一个分类也放行。
     *
     * <p>契约没有把"值没变"列为字段错误（`docs/api-design.md` 6.3 只要求目标分类启用），
     * 服务端保持单一判定；前端负责在"值未变化"时禁用提交按钮，避免用户凭空多出一条调整记录。</p>
     */
    @Test
    void changeCategoryAcceptsTheSameCategoryAsNoOpChange() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(categoryAvailabilityPort.isEnabled(CATEGORY_ID)).thenReturn(true);
        when(ticketMapper.changeCategory(TICKET_ID, 5L, IT_USER_ID, CATEGORY_ID, NOW_UTC))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 6, 6L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        service.changeCategory(TICKET_NO,
                new ChangeCategoryCommand(5L, CATEGORY_ID, "确认分类无误"));

        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        assertThat(records.getValue().getFromCategoryId())
                .as("原分类与新分类相同，仍然留下一条可追溯记录")
                .isEqualTo(CATEGORY_ID);
        assertThat(records.getValue().getToCategoryId()).isEqualTo(CATEGORY_ID);
    }

    @Test
    void changeCategoryReportsConflictWithReloadedSnapshotWhenConditionalUpdateLoses() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(categoryAvailabilityPort.isEnabled(NEW_CATEGORY_ID)).thenReturn(true);
        when(ticketMapper.changeCategory(TICKET_ID, 5L, IT_USER_ID, NEW_CATEGORY_ID, NOW_UTC))
                .thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID))
                .thenReturn(conflictSnapshot(WAITING_FOR_REQUESTER, 6L));

        ApiException exception = assertApiException(
                () -> service.changeCategory(TICKET_NO,
                        new ChangeCategoryCommand(5L, NEW_CATEGORY_ID, "分类选错了")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("携带读回的最新版本").isEqualTo(6L);
        assertThat(exception.resourceStatus()).as("携带读回的最新状态")
                .isEqualTo(WAITING_FOR_REQUESTER);
        verify(ticketMapper, never()).selectById(TICKET_ID);
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void changeCategoryFailsWhenConflictSnapshotCannotBeRead() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(categoryAvailabilityPort.isEnabled(NEW_CATEGORY_ID)).thenReturn(true);
        when(ticketMapper.changeCategory(TICKET_ID, 5L, IT_USER_ID, NEW_CATEGORY_ID, NOW_UTC))
                .thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.changeCategory(TICKET_NO,
                new ChangeCategoryCommand(5L, NEW_CATEGORY_ID, "分类选错了")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("调整分类冲突后无法读取工单快照");
    }

    @Test
    void changeCategoryFailsWhenUpdatedTicketSnapshotIsMissing() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(categoryAvailabilityPort.isEnabled(NEW_CATEGORY_ID)).thenReturn(true);
        when(ticketMapper.changeCategory(TICKET_ID, 5L, IT_USER_ID, NEW_CATEGORY_ID, NOW_UTC))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.changeCategory(TICKET_NO,
                new ChangeCategoryCommand(5L, NEW_CATEGORY_ID, "分类选错了")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("调整分类后无法读取工单快照");

        verifyNoInteractions(ticketRecordMapper);
    }

    // ---------- changePriority ----------

    @Test
    void changePriorityRejectsActorWithoutProcessAuthority() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_PROCESS")).thenReturn(false);

        assertApiException(
                () -> service.changePriority(TICKET_NO,
                        new ChangePriorityCommand(5L, LOW, "影响面缩小")),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verify(ticketMapper, never())
                .selectVisibleDetail(any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(ticketMapper, never())
                .changePriority(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void changePriorityReportsNotFoundWhenTicketIsNotVisible() {
        stubProcessActor();

        assertApiException(
                () -> service.changePriority(TICKET_NO,
                        new ChangePriorityCommand(5L, LOW, "影响面缩小")),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verify(ticketMapper, never())
                .changePriority(anyLong(), anyLong(), anyLong(), any(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void changePriorityReportsConflictWhenTicketIsNotAdjustable() {
        stubProcessActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 5L));

        ApiException exception = assertApiException(
                () -> service.changePriority(TICKET_NO,
                        new ChangePriorityCommand(5L, LOW, "影响面缩小")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(5L);
        assertThat(exception.resourceStatus()).isEqualTo(WAITING_FOR_CONFIRMATION);
        verify(ticketMapper, never())
                .changePriority(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void changePriorityReportsConflictWhenActorIsNotTheCurrentAssignee() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, OTHER_IT_USER_ID, 5L));

        assertApiException(
                () -> service.changePriority(TICKET_NO,
                        new ChangePriorityCommand(5L, LOW, "影响面缩小")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never())
                .changePriority(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void changePriorityReportsConflictWhenTicketHasNoAssignee() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, null, 5L));

        assertApiException(
                () -> service.changePriority(TICKET_NO,
                        new ChangePriorityCommand(5L, LOW, "影响面缩小")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never())
                .changePriority(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void changePriorityReportsConflictWhenCommandVersionIsStale() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        ApiException exception = assertApiException(
                () -> service.changePriority(TICKET_NO,
                        new ChangePriorityCommand(4L, LOW, "影响面缩小")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(5L);
        assertThat(exception.resourceStatus()).isEqualTo(PROCESSING);
    }

    /** 校验顺序：状态/负责人/版本冲突先于原因长度与优先级取值。 */
    @Test
    void changePriorityReportsConflictBeforeValidatingReasonAndPriority() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.changePriority(TICKET_NO,
                        new ChangePriorityCommand(4L, "URGENT", "   ")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never())
                .changePriority(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void changePriorityRejectsBlankReasonWhenVersionMatches() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.changePriority(TICKET_NO,
                        new ChangePriorityCommand(5L, LOW, "   ")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never())
                .changePriority(anyLong(), anyLong(), anyLong(), any(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void changePriorityRejectsNullReasonWithoutThrowing() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.changePriority(TICKET_NO,
                        new ChangePriorityCommand(5L, LOW, null)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never())
                .changePriority(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void changePriorityRejectsReasonOverLimit() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.changePriority(TICKET_NO,
                        new ChangePriorityCommand(5L, LOW, "a".repeat(1001))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never())
                .changePriority(anyLong(), anyLong(), anyLong(), any(), any());
    }

    /**
     * 优先级取值以 {@code TicketPriority} 枚举为准。
     *
     * <p>HTTP 入口的 {@code @Pattern} 会挡住这些取值，但服务被脚本、集成测试或其它服务
     * 直接调用时不走 Bean Validation，因此服务层必须自己兜底，而不是等数据库的
     * {@code ck_ticket_priority} 抛异常变成 500。</p>
     */
    @ParameterizedTest(name = "priority [{0}] is rejected")
    @NullSource
    @ValueSource(strings = {"low", "Urgent", "URGENT", "", "   "})
    void changePriorityRejectsPriorityOutsideEnum(String priority) {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.changePriority(TICKET_NO,
                        new ChangePriorityCommand(5L, priority, "影响面缩小")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never())
                .changePriority(anyLong(), anyLong(), anyLong(), any(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    /** 成功路径：原优先级 → 新优先级 + 说明，状态与负责人都不动。 */
    @Test
    void changePriorityReplacesPriorityAndKeepsStatusAndAssignee() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(ticketMapper.changePriority(TICKET_ID, 5L, IT_USER_ID, LOW, NOW_UTC)).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 6, 6L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.changePriority(TICKET_NO,
                new ChangePriorityCommand(5L, LOW, "  影响面缩小  "));

        verify(ticketMapper).changePriority(TICKET_ID, 5L, IT_USER_ID, LOW, NOW_UTC);
        verify(ticketMapper, never())
                .changeCategory(anyLong(), anyLong(), anyLong(), anyLong(), any());

        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        TicketRecord record = records.getValue();
        assertThat(record.getRecordType()).isEqualTo("PRIORITY_CHANGE");
        assertThat(record.getSequenceNo()).isEqualTo(6);
        assertThat(record.getActorUserId()).isEqualTo(IT_USER_ID);
        assertThat(record.getReason()).isEqualTo("影响面缩小");
        assertThat(record.getFromPriority()).isEqualTo(HIGH);
        assertThat(record.getToPriority()).isEqualTo(LOW);
        assertThat(record.getFromCategoryId()).as("优先级调整不写分类快照").isNull();
        assertThat(record.getToCategoryId()).isNull();
        assertThat(record.getFromStatus()).isEqualTo(PROCESSING);
        assertThat(record.getToStatus()).isEqualTo(PROCESSING);
        assertThat(record.getCreatedAt()).isEqualTo(NOW_UTC);

        assertThat(result.status()).isEqualTo(PROCESSING);
        assertThat(result.assignee())
                .isEqualTo(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        assertThat(result.actionDeadlineAt()).isNull();
        assertThat(result.version()).isEqualTo(6L);
    }

    /** 「待补充」也能调整优先级：期限原样有效，不因为一次调整而失效。 */
    @Test
    void changePriorityKeepsSupplementDeadlineWhileWaitingForRequester() {
        stubProcessActor();
        TicketDetailRow row = detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L);
        row.setActionDeadlineAt(SUPPLEMENT_DEADLINE);
        stubVisible(row);
        when(ticketMapper.changePriority(TICKET_ID, 5L, IT_USER_ID, LOW, NOW_UTC)).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 6, 6L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.changePriority(TICKET_NO,
                new ChangePriorityCommand(5L, LOW, "影响面缩小"));

        assertThat(result.status()).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(result.actionDeadlineAt())
                .isEqualTo(SUPPLEMENT_DEADLINE.atOffset(ZoneOffset.UTC));
    }

    @Test
    void changePriorityReportsConflictWithReloadedSnapshotWhenConditionalUpdateLoses() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(ticketMapper.changePriority(TICKET_ID, 5L, IT_USER_ID, LOW, NOW_UTC)).thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID))
                .thenReturn(conflictSnapshot(PROCESSING, 7L));

        ApiException exception = assertApiException(
                () -> service.changePriority(TICKET_NO,
                        new ChangePriorityCommand(5L, LOW, "影响面缩小")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("携带读回的最新版本").isEqualTo(7L);
        assertThat(exception.resourceStatus()).isEqualTo(PROCESSING);
        verify(ticketMapper, never()).selectById(TICKET_ID);
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void changePriorityFailsWhenConflictSnapshotCannotBeRead() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(ticketMapper.changePriority(TICKET_ID, 5L, IT_USER_ID, LOW, NOW_UTC)).thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.changePriority(TICKET_NO,
                new ChangePriorityCommand(5L, LOW, "影响面缩小")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("调整优先级冲突后无法读取工单快照");
    }

    @Test
    void changePriorityFailsWhenUpdatedTicketSnapshotIsMissing() {
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(ticketMapper.changePriority(TICKET_ID, 5L, IT_USER_ID, LOW, NOW_UTC)).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.changePriority(TICKET_NO,
                new ChangePriorityCommand(5L, LOW, "影响面缩小")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("调整优先级后无法读取工单快照");
    }

    // ---------- transfer ----------

    /** 转交单独要求 {@code TICKET_TRANSFER}，且权限闸门先于可见性。 */
    @Test
    void transferRejectsActorWithoutTransferAuthority() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_TRANSFER")).thenReturn(false);

        assertApiException(
                () -> service.transfer(TICKET_NO, new TransferCommand(5L, NEW_IT_USER_ID, "换人跟进")),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verify(ticketMapper, never())
                .selectVisibleDetail(any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
        verifyNoInteractions(ticketClaimPort);
    }

    @Test
    void transferReportsNotFoundWhenTicketIsNotVisible() {
        stubTransferActor();

        assertApiException(
                () -> service.transfer(TICKET_NO, new TransferCommand(5L, NEW_IT_USER_ID, "换人跟进")),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verify(ticketMapper, never())
                .transfer(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(ticketClaimPort);
    }

    @Test
    void transferReportsConflictWhenTicketIsNotAdjustable() {
        stubTransferActor();
        stubVisible(detailRow(PENDING, null, 5L));

        ApiException exception = assertApiException(
                () -> service.transfer(TICKET_NO, new TransferCommand(5L, NEW_IT_USER_ID, "换人跟进")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(5L);
        assertThat(exception.resourceStatus()).isEqualTo(PENDING);
        verifyNoInteractions(ticketClaimPort);
    }

    @Test
    void transferReportsConflictWhenActorIsNotTheCurrentAssignee() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, OTHER_IT_USER_ID, 5L));

        assertApiException(
                () -> service.transfer(TICKET_NO, new TransferCommand(5L, NEW_IT_USER_ID, "换人跟进")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verifyNoInteractions(ticketClaimPort);
    }

    @Test
    void transferReportsConflictWhenTicketHasNoAssignee() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, null, 5L));

        assertApiException(
                () -> service.transfer(TICKET_NO, new TransferCommand(5L, NEW_IT_USER_ID, "换人跟进")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verifyNoInteractions(ticketClaimPort);
    }

    @Test
    void transferReportsConflictWhenCommandVersionIsStale() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        ApiException exception = assertApiException(
                () -> service.transfer(TICKET_NO, new TransferCommand(4L, NEW_IT_USER_ID, "换人跟进")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(5L);
        assertThat(exception.resourceStatus()).isEqualTo(PROCESSING);
        verifyNoInteractions(ticketClaimPort);
    }

    @Test
    void transferReportsConflictBeforeValidatingReasonAndTarget() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.transfer(TICKET_NO, new TransferCommand(4L, IT_USER_ID, "   ")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verifyNoInteractions(ticketClaimPort);
    }

    @Test
    void transferRejectsBlankReasonWhenVersionMatches() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.transfer(TICKET_NO, new TransferCommand(5L, NEW_IT_USER_ID, "   ")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketClaimPort);
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void transferRejectsNullReasonWithoutThrowing() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.transfer(TICKET_NO, new TransferCommand(5L, NEW_IT_USER_ID, null)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketClaimPort);
    }

    @Test
    void transferRejectsReasonOverLimit() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.transfer(TICKET_NO,
                        new TransferCommand(5L, NEW_IT_USER_ID, "a".repeat(1001))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketClaimPort);
    }

    /** 字段级错误先于加锁：转给自己在业务上无意义，也不该占用两把行锁。 */
    @Test
    void transferRejectsTransferToSelfBeforeLocking() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.transfer(TICKET_NO, new TransferCommand(5L, IT_USER_ID, "换人跟进")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketClaimPort);
        verify(ticketMapper, never())
                .transfer(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    /** 转给提交人会被 {@code ck_ticket_assignee_not_requester} 拒绝，服务层先给出 400。 */
    @Test
    void transferRejectsTransferToRequesterBeforeLocking() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.transfer(TICKET_NO, new TransferCommand(5L, REQUESTER_ID, "换人跟进")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(ticketClaimPort);
        verify(ticketMapper, never())
                .transfer(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    /** 锁后复核：在途的停用/撤角色会让 actor 侧判定失败，按 403 返回且不写库。 */
    @Test
    void transferRejectsWhenActorIsNoLongerEligibleAfterLocking() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        stubLockedTicketSnapshot(PROCESSING, 5L);
        when(ticketClaimPort.lockEligibleClaimant(IT_USER_ID)).thenReturn(null);
        when(ticketClaimPort.lockEligibleClaimant(NEW_IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(NEW_IT_USER_ID, NEW_IT_DISPLAY_NAME));

        assertApiException(
                () -> service.transfer(TICKET_NO, new TransferCommand(5L, NEW_IT_USER_ID, "换人跟进")),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verify(ticketMapper, never())
                .transfer(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    /** 锁后复核：目标不是启用的 IT 支持人员时按字段错误返回，不能写进负责人列。 */
    @Test
    void transferRejectsIneligibleNewAssigneeAfterLocking() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        stubLockedTicketSnapshot(PROCESSING, 5L);
        when(ticketClaimPort.lockEligibleClaimant(IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        when(ticketClaimPort.lockEligibleClaimant(NEW_IT_USER_ID)).thenReturn(null);

        assertApiException(
                () -> service.transfer(TICKET_NO, new TransferCommand(5L, NEW_IT_USER_ID, "换人跟进")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never())
                .transfer(anyLong(), anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    /**
     * 片 D 的死锁修法：转交必须**先锁工单行，再锁用户行**。
     *
     * <p>反过来（先锁 {@code iam_user}）时，另一个已经持有工单行锁的动作会因为
     * {@code ticket_record.actor_user_id} 的外键校验去申请同一行 {@code iam_user} 的共享锁，
     * 「user → ticket」与「ticket → user」首尾相接成环，InnoDB 回滚其中一个，
     * 调用方拿到的是 {@code 500} 而不是可重试的 {@code 409}。本用例把顺序钉死。</p>
     */
    @Test
    void transferLocksTicketRowBeforeUserRows() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        stubLockedTicketSnapshot(PROCESSING, 5L);
        when(ticketClaimPort.lockEligibleClaimant(IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        when(ticketClaimPort.lockEligibleClaimant(NEW_IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(NEW_IT_USER_ID, NEW_IT_DISPLAY_NAME));
        when(ticketMapper.transfer(TICKET_ID, 5L, IT_USER_ID, NEW_IT_USER_ID, NOW_UTC))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 6, 6L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        service.transfer(TICKET_NO, new TransferCommand(5L, NEW_IT_USER_ID, "换人跟进"));

        InOrder lockingOrder = inOrder(ticketMapper, ticketClaimPort);
        lockingOrder.verify(ticketMapper).selectClaimConflictSnapshotForUpdate(TICKET_ID);
        lockingOrder.verify(ticketClaimPort).lockEligibleClaimant(IT_USER_ID);
        lockingOrder.verify(ticketClaimPort).lockEligibleClaimant(NEW_IT_USER_ID);
    }

    /**
     * 等待工单行锁期间这张单被别人推进了：锁后的版本复核必须当场给出 409，
     * 不能带着过期快照继续去锁用户行，更不能写库。
     */
    @Test
    void transferReportsConflictWhenVersionChangedWhileWaitingForTheTicketLock() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        stubLockedTicketSnapshot(WAITING_FOR_CONFIRMATION, 6L);

        ApiException exception = assertApiException(
                () -> service.transfer(TICKET_NO, new TransferCommand(5L, NEW_IT_USER_ID, "换人跟进")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("快照来自拿到锁之后重新读到的行").isEqualTo(6L);
        assertThat(exception.resourceStatus()).isEqualTo(WAITING_FOR_CONFIRMATION);
        verifyNoInteractions(ticketClaimPort, ticketRecordMapper, ticketParticipantMapper);
        verify(ticketMapper, never()).transfer(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    /**
     * 固定锁顺序：无论"谁转给谁"，都按 {@code user_id} 升序取两把行锁；
     * 而且两把用户行锁之前，工单行的锁已经在手（片 D 的死锁修法，见 {@link #transferLocksTicketRowBeforeUserRows()}）。
     *
     * <p>若两个事务各自"先锁自己、再锁对方"，{@code A→B} 与 {@code B→A} 并发就是 AB-BA 死锁，
     * InnoDB 会回滚其中一个，用户看到的是本可成功的转交失败。本用例把负责人设成 id 更大的
     * 那一个（9），证明取锁顺序与业务身份无关。</p>
     */
    @Test
    void transferLocksBothUserRowsInAscendingIdOrder() {
        when(currentRequesterPort.currentUserId()).thenReturn(OTHER_IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_TRANSFER")).thenReturn(true);
        stubVisible(detailRow(PROCESSING, OTHER_IT_USER_ID, 5L));
        stubLockedTicketSnapshot(PROCESSING, 5L);
        when(ticketClaimPort.lockEligibleClaimant(IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        when(ticketClaimPort.lockEligibleClaimant(OTHER_IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(OTHER_IT_USER_ID, OTHER_IT_DISPLAY_NAME));
        when(ticketMapper.transfer(TICKET_ID, 5L, OTHER_IT_USER_ID, IT_USER_ID, NOW_UTC))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 6, 6L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.transfer(TICKET_NO,
                new TransferCommand(5L, IT_USER_ID, "转回原负责人"));

        InOrder lockingOrder = inOrder(ticketClaimPort);
        lockingOrder.verify(ticketClaimPort).lockEligibleClaimant(IT_USER_ID);
        lockingOrder.verify(ticketClaimPort).lockEligibleClaimant(OTHER_IT_USER_ID);

        assertThat(result.assignee()).as("取锁顺序不等于语义身份：摘要里是新负责人")
                .isEqualTo(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        assertThat(result.version()).isEqualTo(6L);
    }

    /** 成功路径：负责人原子替换、参与关系落库、时间线记录原负责人 → 新负责人。 */
    @Test
    void transferReplacesAssigneeAndRecordsParticipation() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        stubLockedTicketSnapshot(PROCESSING, 5L);
        when(ticketClaimPort.lockEligibleClaimant(IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        when(ticketClaimPort.lockEligibleClaimant(NEW_IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(NEW_IT_USER_ID, NEW_IT_DISPLAY_NAME));
        when(ticketMapper.transfer(TICKET_ID, 5L, IT_USER_ID, NEW_IT_USER_ID, NOW_UTC))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 6, 6L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.transfer(TICKET_NO,
                new TransferCommand(5L, NEW_IT_USER_ID, "  换人跟进  "));

        verify(ticketMapper).transfer(TICKET_ID, 5L, IT_USER_ID, NEW_IT_USER_ID, NOW_UTC);
        verify(ticketParticipantMapper).recordAssignment(TICKET_ID, NEW_IT_USER_ID, NOW_UTC);

        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        TicketRecord record = records.getValue();
        assertThat(record.getRecordType()).isEqualTo("TRANSFER");
        assertThat(record.getSequenceNo()).isEqualTo(6);
        assertThat(record.getActorUserId()).as("执行者是原负责人").isEqualTo(IT_USER_ID);
        assertThat(record.getReason()).as("说明去除首尾空白后落库").isEqualTo("换人跟进");
        assertThat(record.getContent()).as("转交动作没有正文").isNull();
        assertThat(record.getFromAssigneeId()).isEqualTo(IT_USER_ID);
        assertThat(record.getToAssigneeId()).isEqualTo(NEW_IT_USER_ID);
        assertThat(record.getFromStatus()).as("状态没变，两侧写同一个状态")
                .isEqualTo(PROCESSING);
        assertThat(record.getToStatus()).isEqualTo(PROCESSING);
        assertThat(record.getCreatedAt()).isEqualTo(NOW_UTC);

        assertThat(result.ticketNo()).isEqualTo(TICKET_NO);
        assertThat(result.status()).as("转交不改变状态").isEqualTo(PROCESSING);
        assertThat(result.assignee()).as("摘要里已经是新负责人")
                .isEqualTo(new TicketUserSummaryResult(NEW_IT_USER_ID, NEW_IT_DISPLAY_NAME));
        assertThat(result.actionDeadlineAt()).isNull();
        assertThat(result.version()).isEqualTo(6L);
        assertThat(result.actionTime()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    }

    /** 「待补充」也能转交：期限跟着工单走，新负责人接手时它仍然有效。 */
    @Test
    void transferKeepsSupplementDeadlineWhileWaitingForRequester() {
        stubTransferActor();
        TicketDetailRow row = detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 5L);
        row.setActionDeadlineAt(SUPPLEMENT_DEADLINE);
        stubVisible(row);
        stubLockedTicketSnapshot(WAITING_FOR_REQUESTER, 5L);
        when(ticketClaimPort.lockEligibleClaimant(IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        when(ticketClaimPort.lockEligibleClaimant(NEW_IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(NEW_IT_USER_ID, NEW_IT_DISPLAY_NAME));
        when(ticketMapper.transfer(TICKET_ID, 5L, IT_USER_ID, NEW_IT_USER_ID, NOW_UTC))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 6, 6L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.transfer(TICKET_NO,
                new TransferCommand(5L, NEW_IT_USER_ID, "换人跟进"));

        assertThat(result.status()).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(result.actionDeadlineAt())
                .isEqualTo(SUPPLEMENT_DEADLINE.atOffset(ZoneOffset.UTC));
    }

    @Test
    void transferReportsConflictWithReloadedSnapshotWhenConditionalUpdateLoses() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(ticketClaimPort.lockEligibleClaimant(IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        when(ticketClaimPort.lockEligibleClaimant(NEW_IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(NEW_IT_USER_ID, NEW_IT_DISPLAY_NAME));
        when(ticketMapper.transfer(TICKET_ID, 5L, IT_USER_ID, NEW_IT_USER_ID, NOW_UTC))
                .thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID))
                .thenReturn(conflictSnapshot(PROCESSING, 5L), conflictSnapshot(PROCESSING, 6L));

        ApiException exception = assertApiException(
                () -> service.transfer(TICKET_NO, new TransferCommand(5L, NEW_IT_USER_ID, "换人跟进")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("携带读回的最新版本").isEqualTo(6L);
        assertThat(exception.resourceStatus()).isEqualTo(PROCESSING);
        verify(ticketMapper, never()).selectById(TICKET_ID);
        verifyNoInteractions(ticketRecordMapper);
        verify(ticketParticipantMapper, never())
                .recordAssignment(anyLong(), anyLong(), any());
    }

    @Test
    void transferFailsWhenConflictSnapshotCannotBeRead() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(ticketClaimPort.lockEligibleClaimant(IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        when(ticketClaimPort.lockEligibleClaimant(NEW_IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(NEW_IT_USER_ID, NEW_IT_DISPLAY_NAME));
        when(ticketMapper.transfer(TICKET_ID, 5L, IT_USER_ID, NEW_IT_USER_ID, NOW_UTC))
                .thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID))
                .thenReturn(conflictSnapshot(PROCESSING, 5L), (Ticket) null);

        assertThatThrownBy(() -> service.transfer(TICKET_NO,
                new TransferCommand(5L, NEW_IT_USER_ID, "换人跟进")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("转交冲突后无法读取工单快照");
    }

    @Test
    void transferFailsWhenUpdatedTicketSnapshotIsMissing() {
        stubTransferActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        stubLockedTicketSnapshot(PROCESSING, 5L);
        when(ticketClaimPort.lockEligibleClaimant(IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        when(ticketClaimPort.lockEligibleClaimant(NEW_IT_USER_ID))
                .thenReturn(new TicketUserSummaryResult(NEW_IT_USER_ID, NEW_IT_DISPLAY_NAME));
        when(ticketMapper.transfer(TICKET_ID, 5L, IT_USER_ID, NEW_IT_USER_ID, NOW_UTC))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.transfer(TICKET_NO,
                new TransferCommand(5L, NEW_IT_USER_ID, "换人跟进")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("转交后无法读取工单快照");

        verify(ticketParticipantMapper, never())
                .recordAssignment(anyLong(), anyLong(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    /** 片 C 的三个动作同样必须由声明式事务包住条件更新、参与关系与时间线写入。 */
    @Test
    void adjustAndTransferMethodsDeclareTransactionBoundaries() throws NoSuchMethodException {
        assertThat(TicketServiceImpl.class
                .getMethod("changeCategory", String.class, ChangeCategoryCommand.class)
                .isAnnotationPresent(Transactional.class))
                .as("changeCategory 由声明式事务包住条件更新与时间线写入").isTrue();
        assertThat(TicketServiceImpl.class
                .getMethod("changePriority", String.class, ChangePriorityCommand.class)
                .isAnnotationPresent(Transactional.class))
                .as("changePriority 由声明式事务包住条件更新与时间线写入").isTrue();
        assertThat(TicketServiceImpl.class
                .getMethod("transfer", String.class, TransferCommand.class)
                .isAnnotationPresent(Transactional.class))
                .as("transfer 由声明式事务包住两把行锁、条件更新与时间线写入").isTrue();
    }

    // ---------- close ----------

    /**
     * 关闭是「两条授权同时成立」的动作，这是 2026-10-08 的用户裁决：关闭结束整张工单，
     * 必须建立在处理权限之上；片 C 的 {@code transfer} 只要求 {@code TICKET_TRANSFER}，
     * 两者口径不同是有意的。
     */
    @Test
    void closeRejectsActorWithoutProcessAuthority() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_CLOSE")).thenReturn(true);

        assertApiException(
                () -> service.close(TICKET_NO, closeCommand(5L, "OUT_OF_SCOPE", "超出支持范围")),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verify(ticketMapper, never())
                .selectVisibleDetail(any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
        verifyNoInteractions(ticketRelationMapper);
    }

    /** 反向的一格：只有 {@code TICKET_PROCESS} 而没有 {@code TICKET_CLOSE} 同样关闭不了。 */
    @Test
    void closeRejectsActorWithoutCloseAuthority() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_PROCESS")).thenReturn(true);

        assertApiException(
                () -> service.close(TICKET_NO, closeCommand(5L, "OUT_OF_SCOPE", "超出支持范围")),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verify(ticketMapper, never())
                .selectVisibleDetail(any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(ticketMapper, never()).closeManually(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void closeReportsNotFoundWhenTicketIsNotVisible() {
        stubCloseActor();

        assertApiException(
                () -> service.close(TICKET_NO, closeCommand(5L, "OUT_OF_SCOPE", "超出支持范围")),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verify(ticketMapper, never()).closeManually(anyLong(), anyLong(), anyLong(), any(), any());
        verifyNoInteractions(ticketRelationMapper);
    }

    /**
     * 只有「处理中」能关闭：其余六个状态（含三个终态）都是 409，而不是 400 或 404。
     *
     * <p>参数里带上 {@code PENDING}（无人负责）与三个终态，是为了让「状态白名单」这件事
     * 由一条用例整体钉住——只测一个 {@code WAITING_FOR_REQUESTER} 无法发现将来误放宽。</p>
     */
    @ParameterizedTest(name = "close is a conflict on {0}")
    @ValueSource(strings = {PENDING, WAITING_FOR_REQUESTER, WAITING_FOR_CONFIRMATION,
            COMPLETED, CANCELED, CLOSED})
    void closeReportsConflictForEveryStatusOtherThanProcessing(String status) {
        stubCloseActor();
        Long assigneeId = PENDING.equals(status) ? null : IT_USER_ID;
        stubVisible(detailRow(status, assigneeId, 5L));

        ApiException exception = assertApiException(
                () -> service.close(TICKET_NO, closeCommand(5L, "INVALID", "无效工单")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(5L);
        assertThat(exception.resourceStatus()).isEqualTo(status);
        verify(ticketMapper, never()).closeManually(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void closeReportsConflictWhenActorIsNotTheCurrentAssignee() {
        stubCloseActor();
        stubVisible(detailRow(PROCESSING, OTHER_IT_USER_ID, 5L));

        assertApiException(
                () -> service.close(TICKET_NO, closeCommand(5L, "INVALID", "无效工单")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never()).closeManually(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void closeReportsConflictWhenTicketHasNoAssignee() {
        stubCloseActor();
        stubVisible(detailRow(PROCESSING, null, 5L));

        assertApiException(
                () -> service.close(TICKET_NO, closeCommand(5L, "INVALID", "无效工单")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never()).closeManually(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void closeReportsConflictWhenCommandVersionIsStale() {
        stubCloseActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        ApiException exception = assertApiException(
                () -> service.close(TICKET_NO, closeCommand(4L, "INVALID", "无效工单")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("冲突响应携带库里最新版本").isEqualTo(5L);
        assertThat(exception.resourceStatus()).isEqualTo(PROCESSING);
    }

    /** 顺序陷阱：版本过期 + 非法原因码 + 空白说明，仍然必须是 409 而不是 400。 */
    @Test
    void closeReportsConflictBeforeValidatingReasonAndDescription() {
        stubCloseActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.close(TICKET_NO, closeCommand(4L, "NOT_A_REASON", "   ")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never()).closeManually(anyLong(), anyLong(), anyLong(), any(), any());
    }

    /**
     * 人工关闭只接受三种标准原因。{@code REQUESTER_NO_RESPONSE} 是「员工逾期未补充」的
     * 系统自动关闭（{@code close_method = AUTO_SUPPLEMENT_TIMEOUT}），必须由服务层挡住——
     * HTTP 入口的 {@code @Pattern} 管不到脚本与将来的内部调用方。
     */
    @ParameterizedTest(name = "close rejects reason code [{0}]")
    @ValueSource(strings = {"", "MANUAL", "REQUESTER_NO_RESPONSE", "duplicate", "OUT_OF_SCOPE "})
    void closeRejectsReasonCodeOutsideTheManualSet(String reasonCode) {
        stubCloseActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.close(TICKET_NO, closeCommand(5L, reasonCode, "无效工单")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).closeManually(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @ParameterizedTest(name = "close rejects description length {0}")
    @ValueSource(ints = {0, 1001})
    void closeRejectsDescriptionOutsideOneToThousand(int length) {
        stubCloseActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.close(TICKET_NO, closeCommand(5L, "INVALID", "x".repeat(length))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).closeManually(anyLong(), anyLong(), anyLong(), any(), any());
    }

    /** 非 HTTP 调用方传 null 说明：必须是 400，而不是 NPE。 */
    @Test
    void closeRejectsNullDescriptionWithoutThrowing() {
        stubCloseActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.close(TICKET_NO, closeCommand(5L, "INVALID", null)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    /** 跨字段规则：只有「重复工单」才允许带重复单号，其余原因带了一律 400。 */
    @Test
    void closeRejectsDuplicateTicketNoWhenReasonIsNotDuplicate() {
        stubCloseActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.close(TICKET_NO, new CloseTicketCommand(
                        5L, "INVALID", "无效工单", "FD-20261006-002")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).selectDuplicateTarget(anyLong(), anyLong(), any());
        verify(ticketMapper, never()).closeManually(anyLong(), anyLong(), anyLong(), any(), any());
    }

    /** 反向的一格：重复原因必须给出目标编号，缺了就是 400，而不是关成一张"没有重复对象"的单。 */
    @Test
    void closeRejectsDuplicateReasonWithoutTicketNo() {
        stubCloseActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));

        assertApiException(
                () -> service.close(TICKET_NO, new CloseTicketCommand(
                        5L, "DUPLICATE", "与另一张单重复", null)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).selectDuplicateTarget(anyLong(), anyLong(), any());
        verify(ticketMapper, never()).closeManually(anyLong(), anyLong(), anyLong(), any(), any());
    }

    /**
     * 目标不存在、属于他人、是自己或已是终态，在 SQL 里都归成"查不到"，统一 400。
     *
     * <p>"同一提交人"写进 {@code WHERE} 而不是查出来再判，正是为了不向调用方回显
     * 他人的工单是否存在；因此这四种情况在服务层是同一条分支。</p>
     */
    @Test
    void closeRejectsDuplicateTargetThatCannotBeResolved() {
        stubCloseActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(ticketMapper.selectDuplicateTarget(TICKET_ID, REQUESTER_ID, "FD-20261006-002"))
                .thenReturn(null);

        assertApiException(
                () -> service.close(TICKET_NO, new CloseTicketCommand(
                        5L, "DUPLICATE", "与另一张单重复", "FD-20261006-002")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).closeManually(anyLong(), anyLong(), anyLong(), any(), any());
        verifyNoInteractions(ticketRelationMapper);
    }

    /**
     * 人工关闭的真实写入：状态、关闭字段与结束时间必须由条件更新一句写完。
     *
     * <p>拆开会撞 {@code ck_ticket_status_ended}（终态必须有结束时间）；
     * 负责人不写，按快照保留为历史信息。</p>
     */
    @Test
    void closeWritesTerminalStateAndClosureRecord() {
        stubCloseActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(ticketMapper.closeManually(TICKET_ID, 5L, IT_USER_ID, "OUT_OF_SCOPE", NOW_UTC))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(closedTicket(6L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.close(TICKET_NO,
                new CloseTicketCommand(5L, "OUT_OF_SCOPE", "  超出支持范围  ", "   "));

        assertThat(result.ticketNo()).isEqualTo(TICKET_NO);
        assertThat(result.status()).isEqualTo(CLOSED);
        assertThat(result.assignee()).as("关闭后负责人保留为历史信息")
                .isEqualTo(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        assertThat(result.actionDeadlineAt()).as("终态不再有待办期限").isNull();
        assertThat(result.version()).isEqualTo(6L);
        assertThat(result.actionTime()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));

        ArgumentCaptor<TicketRecord> captor = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(captor.capture());
        TicketRecord record = captor.getValue();
        assertThat(record.getRecordType()).isEqualTo("CLOSURE");
        assertThat(record.getActorType()).isEqualTo("USER");
        assertThat(record.getActorUserId()).isEqualTo(IT_USER_ID);
        assertThat(record.getReason()).as("关闭说明去掉首尾空白后进 reason 列")
                .isEqualTo("超出支持范围");
        assertThat(record.getCloseMethod()).as("人工关闭与超时自动关闭必须可区分")
                .isEqualTo("MANUAL");
        assertThat(record.getCloseReason()).isEqualTo("OUT_OF_SCOPE");
        assertThat(record.getFromStatus()).isEqualTo(PROCESSING);
        assertThat(record.getToStatus()).isEqualTo(CLOSED);
        assertThat(record.getSequenceNo()).isEqualTo(6);
        assertThat(record.getCreatedAt()).isEqualTo(NOW_UTC);

        verifyNoInteractions(ticketRelationMapper);
    }

    /** 重复关闭必须落一条有向关联：source 是被关闭的本单，target 是解析到的有效目标。 */
    @Test
    void closeWritesDuplicateRelationToTheResolvedTarget() {
        stubCloseActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(ticketMapper.selectDuplicateTarget(TICKET_ID, REQUESTER_ID, "FD-20261006-002"))
                .thenReturn(duplicateTargetRow(2002L, "FD-20261006-002", PENDING));
        when(ticketMapper.closeManually(TICKET_ID, 5L, IT_USER_ID, "DUPLICATE", NOW_UTC))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(closedTicket(6L));
        when(ticketRelationMapper.recordDuplicate(TICKET_ID, 2002L, IT_USER_ID, NOW_UTC))
                .thenReturn(1);
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.close(TICKET_NO, new CloseTicketCommand(
                5L, "DUPLICATE", "与另一张单重复", "  FD-20261006-002  "));

        assertThat(result.status()).isEqualTo(CLOSED);
        assertThat(result.version()).isEqualTo(6L);
        verify(ticketRelationMapper).recordDuplicate(TICKET_ID, 2002L, IT_USER_ID, NOW_UTC);
    }

    /** 关联写入失败必须让整个事务回滚，而不是留下一张"说是重复却指不到谁"的已关闭工单。 */
    @Test
    void closeFailsWhenDuplicateRelationCannotBeWritten() {
        stubCloseActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(ticketMapper.selectDuplicateTarget(TICKET_ID, REQUESTER_ID, "FD-20261006-002"))
                .thenReturn(duplicateTargetRow(2002L, "FD-20261006-002", PENDING));
        when(ticketMapper.closeManually(TICKET_ID, 5L, IT_USER_ID, "DUPLICATE", NOW_UTC))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(closedTicket(6L));
        when(ticketRelationMapper.recordDuplicate(TICKET_ID, 2002L, IT_USER_ID, NOW_UTC))
                .thenReturn(0);

        assertThatThrownBy(() -> service.close(TICKET_NO, new CloseTicketCommand(
                5L, "DUPLICATE", "与另一张单重复", "FD-20261006-002")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复工单关联写入失败");

        verifyNoInteractions(ticketRecordMapper);
    }

    /** 终态本身也是一次状态迁移：条件更新输了就按冲突返回，并带回真实快照。 */
    @Test
    void closeReportsConflictWhenConditionalUpdateLosesTheRace() {
        stubCloseActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(ticketMapper.closeManually(TICKET_ID, 5L, IT_USER_ID, "INVALID", NOW_UTC))
                .thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID))
                .thenReturn(conflictSnapshot(WAITING_FOR_CONFIRMATION, 6L));

        ApiException exception = assertApiException(
                () -> service.close(TICKET_NO, closeCommand(5L, "INVALID", "无效工单")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(6L);
        assertThat(exception.resourceStatus()).isEqualTo(WAITING_FOR_CONFIRMATION);
        verifyNoInteractions(ticketRecordMapper, ticketRelationMapper);
    }

    @Test
    void closeFailsWhenUpdatedTicketSnapshotIsMissing() {
        stubCloseActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 5L));
        when(ticketMapper.closeManually(TICKET_ID, 5L, IT_USER_ID, "INVALID", NOW_UTC))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.close(TICKET_NO, closeCommand(5L, "INVALID", "无效工单")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("关闭后无法读取工单快照");

        verifyNoInteractions(ticketRecordMapper, ticketRelationMapper);
    }

    // ---------- cancel ----------

    /** 撤销与补充、确认、未解决反馈共用 {@code TICKET_REQUESTER_ACTION}，闸门先于可见性。 */
    @Test
    void cancelRejectsActorWithoutRequesterActionAuthority() {
        when(currentRequesterPort.currentUserId()).thenReturn(REQUESTER_ID);

        assertApiException(() -> service.cancel(TICKET_NO, cancelCommand(3L, "问题已自行解决")),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verify(ticketMapper, never())
                .selectVisibleDetail(any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    void cancelReportsNotFoundWhenTicketIsNotVisible() {
        stubRequesterActor();

        assertApiException(() -> service.cancel(TICKET_NO, cancelCommand(3L, "问题已自行解决")),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verify(ticketMapper, never()).cancel(anyLong(), anyLong(), anyLong(), any());
    }

    /**
     * 三个终态都不能再撤销：已完成/已关闭是别人正常走完的，已取消是撤销本身的结果。
     *
     * <p>这一格同时是"提交人身份正确、状态不对"的分支——与下面"身份不对"分开测，
     * 两者的错误码相同但触发条件不同。</p>
     */
    @ParameterizedTest(name = "cancel is a conflict on terminal {0}")
    @ValueSource(strings = {COMPLETED, CANCELED, CLOSED})
    void cancelReportsConflictOnTerminalStatus(String status) {
        stubRequesterActor();
        stubVisible(detailRow(status, IT_USER_ID, 3L));

        ApiException exception = assertApiException(
                () -> service.cancel(TICKET_NO, cancelCommand(3L, "问题已自行解决")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(3L);
        assertThat(exception.resourceStatus()).isEqualTo(status);
        verify(ticketMapper, never()).cancel(anyLong(), anyLong(), anyLong(), any());
    }

    /**
     * 当前负责人撤不掉别人的工单：领取之后 IT 是负责人，但提交人始终是员工。
     *
     * <p>这里刻意让 IT 也持有 {@code TICKET_REQUESTER_ACTION}——权限齐备但仍然不是提交人，
     * 撤销必须按冲突拒绝，而不是"有权限就放行"。</p>
     */
    @Test
    void cancelReportsConflictWhenActorIsNotTheRequester() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_REQUESTER_ACTION")).thenReturn(true);
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertApiException(() -> service.cancel(TICKET_NO, cancelCommand(3L, "不需要了")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never()).cancel(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void cancelReportsConflictWhenCommandVersionIsStale() {
        stubRequesterActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        ApiException exception = assertApiException(
                () -> service.cancel(TICKET_NO, cancelCommand(2L, "问题已自行解决")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(3L);
        assertThat(exception.resourceStatus()).isEqualTo(PROCESSING);
    }

    /** 冲突判定先于 400：版本过期 + 空白原因仍然得到 409。 */
    @Test
    void cancelReportsConflictBeforeValidatingReason() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_CONFIRMATION, IT_USER_ID, 3L));

        assertApiException(() -> service.cancel(TICKET_NO, cancelCommand(2L, "   ")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never()).cancel(anyLong(), anyLong(), anyLong(), any());
    }

    /**
     * 四种非终态都能撤销（{@code docs/kickoff.md} 4.7 的逐状态表）。
     *
     * <p>「待补充」与「待确认」带着期限，撤销必须把期限清空——这正是
     * {@code ck_ticket_status_deadline} 要求"非等待态期限为空"的地方，
     * 状态与期限由 Mapper 的同一条 UPDATE 写入，因此这里只能断言入参与结果。</p>
     */
    @ParameterizedTest(name = "cancel from {0}")
    @ValueSource(strings = {PENDING, PROCESSING, WAITING_FOR_REQUESTER, WAITING_FOR_CONFIRMATION})
    void cancelMovesEveryNonTerminalStatusToCanceled(String status) {
        stubRequesterActor();
        Long assigneeId = PENDING.equals(status) ? null : IT_USER_ID;
        stubVisible(detailRow(status, assigneeId, 3L));
        when(ticketMapper.cancel(TICKET_ID, 3L, REQUESTER_ID, NOW_UTC)).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(canceledTicket(4L, assigneeId));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);

        TicketActionResult result = service.cancel(TICKET_NO, cancelCommand(3L, "  问题已自行解决  "));

        assertThat(result.ticketNo()).isEqualTo(TICKET_NO);
        assertThat(result.status()).as("四种非终态都进入已取消").isEqualTo(CANCELED);
        assertThat(result.version()).isEqualTo(4L);
        assertThat(result.actionDeadlineAt()).as("终态不再有待办期限").isNull();
        if (assigneeId == null) {
            assertThat(result.assignee()).as("待受理本来就没有负责人").isNull();
        } else {
            assertThat(result.assignee()).as("其余状态保留最后负责人")
                    .isEqualTo(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        }

        ArgumentCaptor<TicketRecord> captor = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(captor.capture());
        TicketRecord record = captor.getValue();
        assertThat(record.getRecordType()).isEqualTo("CANCELLATION");
        assertThat(record.getActorType()).isEqualTo("USER");
        assertThat(record.getActorUserId()).isEqualTo(REQUESTER_ID);
        assertThat(record.getReason()).as("撤销原因去掉首尾空白后进 reason 列")
                .isEqualTo("问题已自行解决");
        assertThat(record.getFromStatus()).as("记录自己冻结撤销前的状态").isEqualTo(status);
        assertThat(record.getToStatus()).isEqualTo(CANCELED);
        assertThat(record.getCompletionMethod()).as("撤销不代表 IT 解决了问题").isNull();
        assertThat(record.getCloseMethod()).as("撤销也不是关闭").isNull();
        assertThat(record.getCloseReason()).isNull();
        assertThat(record.getSequenceNo()).isEqualTo(6);
        assertThat(record.getCreatedAt()).isEqualTo(NOW_UTC);
    }

    /** 非 HTTP 调用方传 null 与纯空白原因：必须是 400，而不是 NPE 或空原因入库。 */
    @ParameterizedTest(name = "cancel rejects blank reason [{0}]")
    @NullSource
    @ValueSource(strings = {"", "   "})
    void cancelRejectsBlankReason(String reason) {
        stubRequesterActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertApiException(() -> service.cancel(TICKET_NO, cancelCommand(3L, reason)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).cancel(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void cancelRejectsReasonOverOneThousandCharacters() {
        stubRequesterActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));

        assertApiException(
                () -> service.cancel(TICKET_NO, cancelCommand(3L, "x".repeat(1001))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).cancel(anyLong(), anyLong(), anyLong(), any());
    }

    /** 条件更新是唯一胜者判定：输了就按冲突返回，并带回库里真实的版本与状态。 */
    @Test
    void cancelReportsConflictWhenConditionalUpdateLosesTheRace() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, IT_USER_ID, 3L));
        when(ticketMapper.cancel(TICKET_ID, 3L, REQUESTER_ID, NOW_UTC)).thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID))
                .thenReturn(conflictSnapshot(PROCESSING, 4L));

        ApiException exception = assertApiException(
                () -> service.cancel(TICKET_NO, cancelCommand(3L, "问题已自行解决")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).isEqualTo(4L);
        assertThat(exception.resourceStatus()).isEqualTo(PROCESSING);
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void cancelFailsWhenUpdatedTicketSnapshotIsMissing() {
        stubRequesterActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, 3L));
        when(ticketMapper.cancel(TICKET_ID, 3L, REQUESTER_ID, NOW_UTC)).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.cancel(TICKET_NO, cancelCommand(3L, "问题已自行解决")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("撤销后无法读取工单快照");

        verifyNoInteractions(ticketRecordMapper);
    }

    // ---------- TicketProperties ----------

    @Test
    void ticketPropertiesDefaultToSevenDaysWhenUnset() {
        assertThat(new TicketProperties(null, null).confirmationWindow())
                .as("未配置时使用已确认的 7×24 小时默认值")
                .isEqualTo(Duration.ofDays(7));
        assertThat(new TicketProperties(null, null).supplementWindow())
                .as("补充期限与确认期限同口径：缺省也是 7×24 小时")
                .isEqualTo(Duration.ofDays(7));
    }

    @Test
    void ticketPropertiesRejectWindowBelowOneMinute() {
        assertThatThrownBy(() -> new TicketProperties(Duration.ofSeconds(30), null))
                .as("小于 1 分钟的确认期限会让员工无法在期限内操作")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("confirmation-window");

        assertThatThrownBy(() -> new TicketProperties(null, Duration.ofSeconds(30)))
                .as("补充期限与确认期限同口径，不能小于 1 分钟")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supplement-window");
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

    /** 片 B 的补充往返（请求补充 + 提交补充）同样必须由声明式事务包住条件更新与时间线写入。 */
    @Test
    void supplementRoundTripMethodsDeclareTransactionBoundaries() throws NoSuchMethodException {
        assertThat(TicketServiceImpl.class
                .getMethod("requestSupplement", String.class, RequestSupplementCommand.class)
                .isAnnotationPresent(Transactional.class))
                .as("requestSupplement 由声明式事务包住条件更新与时间线写入").isTrue();
        assertThat(TicketServiceImpl.class
                .getMethod("supplement", String.class, SupplementCommand.class)
                .isAnnotationPresent(Transactional.class))
                .as("supplement 由声明式事务包住条件更新与时间线写入").isTrue();
    }

    /**
     * 片 D 的两个结束动作同样必须由声明式事务包住。
     *
     * <p>{@code close} 的事务里除条件更新与时间线外还有 {@code ticket_relation} 的写入，
     * 关联失败必须连同终态一起回滚；{@code cancel} 的状态与期限是同一条 UPDATE，
     * 事务边界保证"期限清空但状态没变"这种中间态不会被人看见。</p>
     */
    @Test
    void closeAndCancelMethodsDeclareTransactionBoundaries() throws NoSuchMethodException {
        assertThat(TicketServiceImpl.class
                .getMethod("close", String.class, CloseTicketCommand.class)
                .isAnnotationPresent(Transactional.class))
                .as("close 由声明式事务包住条件更新、重复关联与时间线写入").isTrue();
        assertThat(TicketServiceImpl.class
                .getMethod("cancel", String.class, CancelTicketCommand.class)
                .isAnnotationPresent(Transactional.class))
                .as("cancel 由声明式事务包住条件更新与时间线写入").isTrue();
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
                ticketRelationMapper,
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

    /** 转交路径的公共前置：有转交权限、本人是当前负责人。 */
    private void stubTransferActor() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_TRANSFER")).thenReturn(true);
    }

    /**
     * 转交的第一段加锁：先取工单行的写锁（片 D 的死锁修法，顺序见
     * {@link #transferLocksTicketRowBeforeUserRows()}）。
     *
     * <p>拿到锁之后实现会用 {@code version} 复核快照，所以默认回一行与 {@code visible}
     * 同版本的行；需要"锁后版本已变"的场景自己用连续返回写（{@code thenReturn(v5, v6)}）。</p>
     */
    private void stubLockedTicketSnapshot(String status, long version) {
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID))
                .thenReturn(conflictSnapshot(status, version));
    }

    /** 关闭路径的公共前置：两条授权同时成立（2026-10-08 的用户裁决）。 */
    private void stubCloseActor() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_PROCESS")).thenReturn(true);
        when(ticketReadPermissionPort.hasAuthority("TICKET_CLOSE")).thenReturn(true);
    }

    private static CloseTicketCommand closeCommand(long version, String reasonCode, String description) {
        return new CloseTicketCommand(version, reasonCode, description, null);
    }

    private static CancelTicketCommand cancelCommand(long version, String reason) {
        return new CancelTicketCommand(version, reason);
    }

    /** 条件更新之后读回的终态快照：关闭与撤销只差状态与负责人。 */
    private static Ticket endedTicket(String status, Long assigneeId, long version) {
        Ticket ticket = new Ticket();
        ticket.setId(TICKET_ID);
        ticket.setTicketNo(TICKET_NO);
        ticket.setStatus(status);
        ticket.setAssigneeId(assigneeId);
        ticket.setRecordSeq(6);
        ticket.setVersion(version);
        return ticket;
    }

    private static Ticket closedTicket(long version) {
        return endedTicket(CLOSED, IT_USER_ID, version);
    }

    private static Ticket canceledTicket(long version, Long assigneeId) {
        return endedTicket(CANCELED, assigneeId, version);
    }

    private static TicketDuplicateTargetRow duplicateTargetRow(
            long id, String ticketNo, String status) {
        TicketDuplicateTargetRow row = new TicketDuplicateTargetRow();
        row.setId(id);
        row.setTicketNo(ticketNo);
        row.setStatus(status);
        return row;
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

    /** 请求补充路径的公共前置：有处理权限、本人是「处理中」的负责人、版本 3。 */
    private void stubSuccessfulRequestSupplement(long version, Duration supplementWindow) {
        LocalDateTime deadline = NOW_UTC.plus(supplementWindow);
        stubProcessActor();
        stubVisible(detailRow(PROCESSING, IT_USER_ID, version));
        when(ticketMapper.requestSupplement(TICKET_ID, version, IT_USER_ID, deadline, NOW_UTC))
                .thenReturn(1);
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);
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
