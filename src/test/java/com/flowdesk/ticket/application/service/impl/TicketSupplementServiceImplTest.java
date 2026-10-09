package com.flowdesk.ticket.application.service.impl;

import com.flowdesk.common.exception.ApiException;
import com.flowdesk.ticket.application.command.SupplementCommand;
import com.flowdesk.ticket.application.port.CategoryAvailabilityPort;
import com.flowdesk.ticket.application.port.CurrentRequesterPort;
import com.flowdesk.ticket.application.port.TicketClaimantPort;
import com.flowdesk.ticket.application.port.TicketReadPermissionPort;
import com.flowdesk.ticket.application.result.TicketActionResult;
import com.flowdesk.ticket.application.result.TicketUserSummaryResult;
import com.flowdesk.ticket.config.TicketProperties;
import com.flowdesk.ticket.domain.Ticket;
import com.flowdesk.ticket.domain.TicketRecord;
import com.flowdesk.ticket.infrastructure.persistence.TicketDetailRow;
import com.flowdesk.ticket.mapper.TicketDailySequenceMapper;
import com.flowdesk.ticket.mapper.TicketMapper;
import com.flowdesk.ticket.mapper.TicketParticipantMapper;
import com.flowdesk.ticket.mapper.TicketRecordMapper;
import com.flowdesk.ticket.mapper.TicketRelationMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 提交人补充信息（{@code supplement}）的业务规则。
 *
 * <p>契约见 {@code docs/api-design.md} 6.4、业务规则见 {@code docs/kickoff.md} 4.11：
 * 只有提交人能在「待补充」上补充，补充后回到「处理中」、原期限失效、**原负责人继续处理**。</p>
 *
 * <p><b>为什么单独一个测试类</b>：{@link TicketServiceImplTest} 已经有片 A 的四个动作，
 * 继续把补充往返塞进去会突破千行；这里只放片 B 的员工侧动作，{@code requestSupplement}
 * 仍在 {@code TicketServiceImplTest} 里与其它负责人动作相邻。</p>
 *
 * <p>顺序约束与其它动作完全一致，用替身固定下来：权限闸门 403 先于可见性 404、
 * 状态/提交人 409 先于版本 409 先于正文 400、条件更新落败时锁读快照回报 409。</p>
 */
@ExtendWith(MockitoExtension.class)
// 多个用例共享"可见性行 + 事务管理器"替身，未使用的桩不应判定为失败。
@MockitoSettings(strictness = Strictness.LENIENT)
class TicketSupplementServiceImplTest {

    /** 故意带纳秒，用于断言落库前按毫秒截断。 */
    private static final Instant CLOCK_INSTANT = Instant.parse("2026-10-06T08:15:30.123456789Z");
    private static final Clock CLOCK = Clock.fixed(CLOCK_INSTANT, ZoneOffset.UTC);
    private static final Instant NOW = CLOCK_INSTANT.truncatedTo(ChronoUnit.MILLIS);
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private static final long TICKET_ID = 2001L;
    private static final long REQUESTER_ID = 11L;
    private static final long IT_USER_ID = 22L;
    private static final long CATEGORY_ID = 3L;

    private static final String TICKET_NO = "FD-20261006-002";
    private static final String EMPLOYEE_DISPLAY_NAME = "演示员工";
    private static final String IT_DISPLAY_NAME = "演示 IT 支持人员";
    private static final String PROCESSING = "PROCESSING";
    private static final String WAITING_FOR_REQUESTER = "WAITING_FOR_REQUESTER";
    private static final String WAITING_FOR_CONFIRMATION = "WAITING_FOR_CONFIRMATION";
    private static final String HIGH = "HIGH";

    private static final TicketProperties DEFAULT_PROPERTIES =
            new TicketProperties(Duration.ofDays(7), Duration.ofDays(7), Duration.ofDays(3));

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

    // ---------- 门禁顺序 ----------

    /**
     * 权限闸门先于提交人身份：负责人能看到工单、也确实与工单有关，但缺
     * {@code TICKET_REQUESTER_ACTION} 时在读取可见性之前就该被 403 拦下。
     */
    @Test
    void supplementChecksRequesterPermissionBeforeIdentity() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_REQUESTER_ACTION")).thenReturn(false);

        assertApiException(
                () -> service.supplement(TICKET_NO,
                        new SupplementCommand(5L, "已补充打印机型号")),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        verify(ticketMapper, never()).selectVisibleDetail(
                any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    void supplementReportsNotFoundWhenTicketIsNotVisible() {
        stubRequesterActor();
        when(ticketMapper.selectVisibleDetail(
                any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(null);

        assertApiException(
                () -> service.supplement(TICKET_NO,
                        new SupplementCommand(5L, "已补充打印机型号")),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");

        verify(ticketMapper, never()).supplement(anyLong(), anyLong(), anyLong(), any());
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void supplementReportsConflictWhenTicketIsNotWaitingForRequester() {
        stubRequesterActor();
        stubVisible(detailRow(PROCESSING, 5L));

        ApiException exception = assertApiException(
                () -> service.supplement(TICKET_NO,
                        new SupplementCommand(5L, "已补充打印机型号")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceStatus()).isEqualTo(PROCESSING);
        assertThat(exception.resourceVersion()).as("冲突快照取可见行").isEqualTo(5L);
    }

    /** 能看到「待补充」工单但不是提交人（例如当前负责人）：按冲突返回，不回显身份判定。 */
    @Test
    void supplementReportsConflictWhenActorIsNotTheRequester() {
        when(currentRequesterPort.currentUserId()).thenReturn(IT_USER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_REQUESTER_ACTION")).thenReturn(true);
        stubVisible(detailRow(WAITING_FOR_REQUESTER, 5L));

        assertApiException(
                () -> service.supplement(TICKET_NO,
                        new SupplementCommand(5L, "已补充打印机型号")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        verify(ticketMapper, never()).supplement(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void supplementReportsConflictWhenCommandVersionIsStale() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, 6L));

        ApiException exception = assertApiException(
                () -> service.supplement(TICKET_NO,
                        new SupplementCommand(5L, "已补充打印机型号")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("响应携带当前版本供前端刷新").isEqualTo(6L);
        verify(ticketMapper, never()).supplement(anyLong(), anyLong(), anyLong(), any());
    }

    /**
     * 顺序陷阱：工单已被别人推进（如负责人撤回）而正文也是空白时，必须回报 409 而不是 400——
     * 否则用户会以为"把内容填上就能提交"，而真正的问题是这次补充已经不需要了。
     */
    @Test
    void supplementReportsConflictBeforeValidatingBlankContent() {
        stubRequesterActor();
        stubVisible(detailRow(PROCESSING, 5L));

        assertApiException(
                () -> service.supplement(TICKET_NO, new SupplementCommand(5L, "   ")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");
    }

    // ---------- 正文校验 ----------

    @Test
    void supplementRejectsBlankContentWhenVersionMatches() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, 5L));

        assertApiException(
                () -> service.supplement(TICKET_NO, new SupplementCommand(5L, "   ")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verify(ticketMapper, never()).supplement(anyLong(), anyLong(), anyLong(), any());
    }

    /**
     * 非 HTTP 调用方（脚本、其它服务、测试）不走 Bean Validation，
     * 正文为 null 必须在服务内兜成 400，而不是抛 NPE 变 500。
     */
    @Test
    void supplementRejectsNullContentWithoutThrowing() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, 5L));

        assertApiException(
                () -> service.supplement(TICKET_NO, new SupplementCommand(5L, null)),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void supplementRejectsContentOverLimit() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, 5L));

        assertApiException(
                () -> service.supplement(TICKET_NO,
                        new SupplementCommand(5L, "补".repeat(10001))),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void supplementAcceptsContentAtExactLimit() {
        stubSuccessfulSupplement(5L);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 7, 6L));

        TicketActionResult result = service.supplement(TICKET_NO,
                new SupplementCommand(5L, "补".repeat(10000)));

        assertThat(result.status()).as("正文恰好 10000 字符是合法请求").isEqualTo(PROCESSING);
    }

    // ---------- 成功路径 ----------

    @Test
    void supplementReturnsTicketToProcessingAndClearsDeadline() {
        stubSuccessfulSupplement(5L);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 7, 6L));

        TicketActionResult result = service.supplement(TICKET_NO,
                new SupplementCommand(5L, "  型号是 M404dn，错误码 E5  "));

        verify(ticketMapper).supplement(TICKET_ID, 5L, REQUESTER_ID, NOW_UTC);

        ArgumentCaptor<TicketRecord> records = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(records.capture());
        TicketRecord record = records.getValue();
        assertThat(record.getRecordType()).isEqualTo("REQUESTER_SUPPLEMENT");
        assertThat(record.getSequenceNo()).as("序号取递增后的 recordSeq").isEqualTo(7);
        assertThat(record.getActorType()).isEqualTo("USER");
        assertThat(record.getActorUserId()).isEqualTo(REQUESTER_ID);
        assertThat(record.getContent()).as("补充正文去除首尾空白后落库")
                .isEqualTo("型号是 M404dn，错误码 E5");
        assertThat(record.getReason()).as("补充信息不是原因类动作").isNull();
        assertThat(record.getFromStatus()).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(record.getToStatus()).isEqualTo(PROCESSING);
        assertThat(record.getDeadlineAt())
                .as("期限已失效，记录不写 deadline；工单侧由条件更新一并清空").isNull();
        assertThat(record.getCreatedAt()).as("落库时间按 UTC 毫秒截断").isEqualTo(NOW_UTC);

        assertThat(result.ticketNo()).isEqualTo(TICKET_NO);
        assertThat(result.status()).isEqualTo(PROCESSING);
        assertThat(result.assignee()).as("补充不等于换人：原负责人保留，摘要取可见快照")
                .isEqualTo(new TicketUserSummaryResult(IT_USER_ID, IT_DISPLAY_NAME));
        assertThat(result.actionDeadlineAt()).as("原补充期限已清空").isNull();
        assertThat(result.version()).isEqualTo(6L);
        assertThat(result.actionTime()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
    }

    /**
     * 期限已过仍可补充：本版本没有超时自动关闭（自动任务属完整版 backlog 第 3 项），
     * 期限只用于界面展示。若这里拦住过期提交，等于把"系统还没实现自动关闭"变成
     * "员工连信息都补不进来"——比不做更糟。
     */
    @Test
    void supplementStillSucceedsAfterDeadlineHasPassed() {
        stubSuccessfulSupplement(5L);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 7, 6L));

        TicketDetailRow overdue = detailRow(WAITING_FOR_REQUESTER, 5L);
        overdue.setActionDeadlineAt(NOW_UTC.minusDays(3));
        stubVisible(overdue);

        TicketActionResult result = service.supplement(TICKET_NO,
                new SupplementCommand(5L, "抱歉补晚了，型号是 M404dn"));

        verify(ticketMapper).supplement(TICKET_ID, 5L, REQUESTER_ID, NOW_UTC);
        assertThat(result.status()).isEqualTo(PROCESSING);
        assertThat(result.actionDeadlineAt()).isNull();
    }

    // ---------- 条件更新落败与读回失败 ----------

    @Test
    void supplementReportsConflictWithReloadedSnapshotWhenConditionalUpdateLoses() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, 5L));
        when(ticketMapper.supplement(TICKET_ID, 5L, REQUESTER_ID, NOW_UTC)).thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID))
                .thenReturn(conflictSnapshot(PROCESSING, 6L));

        ApiException exception = assertApiException(
                () -> service.supplement(TICKET_NO,
                        new SupplementCommand(5L, "已补充打印机型号")),
                HttpStatus.CONFLICT, "TICKET_CONFLICT");

        assertThat(exception.resourceVersion()).as("携带读回的最新版本").isEqualTo(6L);
        assertThat(exception.resourceStatus()).as("携带读回的最新状态").isEqualTo(PROCESSING);
        verify(ticketMapper, never()).selectById(TICKET_ID);
        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void supplementFailsWhenConflictSnapshotCannotBeRead() {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, 5L));
        when(ticketMapper.supplement(TICKET_ID, 5L, REQUESTER_ID, NOW_UTC)).thenReturn(0);
        when(ticketMapper.selectClaimConflictSnapshotForUpdate(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.supplement(TICKET_NO,
                new SupplementCommand(5L, "已补充打印机型号")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("提交补充信息冲突后无法读取工单快照");
    }

    @Test
    void supplementFailsWhenUpdatedTicketSnapshotIsMissing() {
        stubSuccessfulSupplement(5L);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.supplement(TICKET_NO,
                new SupplementCommand(5L, "已补充打印机型号")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("提交补充信息后无法读取工单快照");

        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void supplementFailsWhenUpdatedRecordSeqIsMissing() {
        stubSuccessfulSupplement(5L);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, null, 6L));

        assertThatThrownBy(() -> service.supplement(TICKET_NO,
                new SupplementCommand(5L, "已补充打印机型号")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("提交补充信息后无法读取工单快照");

        verifyNoInteractions(ticketRecordMapper);
    }

    @Test
    void supplementFailsWhenUpdatedVersionIsMissing() {
        stubSuccessfulSupplement(5L);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 7, null));

        assertThatThrownBy(() -> service.supplement(TICKET_NO,
                new SupplementCommand(5L, "已补充打印机型号")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("提交补充信息后无法读取工单快照");
    }

    /**
     * 时间线写入失败必须让整个动作回滚：工单状态已经回到「处理中」，却没有任何记录解释
     * 这次变化，时间线就不再是可信的历史。
     */
    @Test
    void supplementFailsWhenTimelineInsertReturnsZeroRows() {
        stubSuccessfulSupplement(5L);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(updatedTicket(TICKET_NO, 7, 6L));
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(0);

        assertThatThrownBy(() -> service.supplement(TICKET_NO,
                new SupplementCommand(5L, "已补充打印机型号")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("补充记录插入失败");
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

    private void stubRequesterActor() {
        when(currentRequesterPort.currentUserId()).thenReturn(REQUESTER_ID);
        when(ticketReadPermissionPort.hasAuthority("TICKET_REQUESTER_ACTION")).thenReturn(true);
    }

    /** 补充路径的公共前置：有提交人权限、本人是「待补充」的提交人、版本 5。 */
    private void stubSuccessfulSupplement(long version) {
        stubRequesterActor();
        stubVisible(detailRow(WAITING_FOR_REQUESTER, version));
        when(ticketMapper.supplement(TICKET_ID, version, REQUESTER_ID, NOW_UTC))
                .thenReturn(1);
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);
    }

    private void stubVisible(TicketDetailRow row) {
        when(ticketMapper.selectVisibleDetail(any(), anyLong(), anyBoolean(), anyBoolean(), anyBoolean()))
                .thenReturn(row);
    }

    private static TicketDetailRow detailRow(String status, long version) {
        TicketDetailRow row = new TicketDetailRow();
        row.setId(TICKET_ID);
        row.setTicketNo(TICKET_NO);
        row.setTitle("打印机无法连接");
        row.setCategoryId(CATEGORY_ID);
        row.setCategoryName("办公设备");
        row.setPriority(HIGH);
        row.setStatus(status);
        row.setRequesterId(REQUESTER_ID);
        row.setRequesterDisplayName(EMPLOYEE_DISPLAY_NAME);
        row.setAssigneeId(IT_USER_ID);
        row.setAssigneeDisplayName(IT_DISPLAY_NAME);
        row.setActionDeadlineAt(NOW_UTC.plus(Duration.ofDays(7)));
        row.setVersion(version);
        row.setCreatedAt(NOW_UTC);
        row.setUpdatedAt(NOW_UTC);
        return row;
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
