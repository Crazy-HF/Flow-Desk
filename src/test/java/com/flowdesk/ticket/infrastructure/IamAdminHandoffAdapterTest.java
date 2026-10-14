package com.flowdesk.ticket.infrastructure;

import com.flowdesk.common.exception.ApiException;
import com.flowdesk.iam.application.command.AdminHandoffInstruction;
import com.flowdesk.iam.application.result.AdminHandoffAssignmentResult;
import com.flowdesk.iam.domain.IamUser;
import com.flowdesk.iam.domain.IamUserStatus;
import com.flowdesk.iam.mapper.IamUserMapper;
import com.flowdesk.ticket.application.port.TicketClaimantPort;
import com.flowdesk.ticket.application.result.TicketUserSummaryResult;
import com.flowdesk.ticket.domain.Ticket;
import com.flowdesk.ticket.domain.TicketRecord;
import com.flowdesk.ticket.infrastructure.persistence.AdminHandoffTicketRow;
import com.flowdesk.ticket.mapper.TicketMapper;
import com.flowdesk.ticket.mapper.TicketParticipantMapper;
import com.flowdesk.ticket.mapper.TicketRecordMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理性交接端口的工单侧实现（{@code docs/api-design.md} 8.3）。
 *
 * <p>这里固定的是"交接这件事本身"的判定与顺序：权威集合来自数据库而不是请求、漏交与多交、
 * 版本与候选资格漂移、<strong>工单行锁先于用户行锁</strong>，以及一张工单要落下的三处写入
 * （工单行、参与关系、{@code ADMIN_HANDOFF} 记录）。事务边界由调用方 IAM 提供，真实 MySQL 行为
 * 由 {@code IamUserServiceIT} 覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IamAdminHandoffAdapterTest {

    private static final long TARGET_USER_ID = 42L;
    private static final long OPERATOR_ID = 3L;
    private static final long NEW_ASSIGNEE_ID = 9L;
    private static final long REQUESTER_ID = 77L;
    private static final long TICKET_ID = 1001L;
    private static final long TICKET_VERSION = 5L;
    private static final int RECORD_SEQ = 6;
    private static final String TICKET_NO = "FD-20261012-000001";
    private static final String OTHER_TICKET_NO = "FD-20261012-000002";
    private static final String PROCESSING = "PROCESSING";
    private static final String REASON = "账号离职，工单转由他人续办";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 12, 6, 0, 0);

    @Mock
    private TicketMapper ticketMapper;
    @Mock
    private TicketParticipantMapper ticketParticipantMapper;
    @Mock
    private TicketRecordMapper ticketRecordMapper;
    @Mock
    private IamUserMapper iamUserMapper;
    @Mock
    private TicketClaimantPort ticketClaimantPort;

    private IamAdminHandoffAdapter adapter;

    @BeforeEach
    void createAdapter() {
        adapter = new IamAdminHandoffAdapter(
                ticketMapper, ticketParticipantMapper, ticketRecordMapper,
                iamUserMapper, ticketClaimantPort);
        // 记录写入成功是正常路径的默认前提；失败分支由各用例自行覆盖
        when(ticketRecordMapper.insert(any(TicketRecord.class))).thenReturn(1);
        when(ticketParticipantMapper.recordAssignment(anyLong(), anyLong(), any())).thenReturn(1);
    }

    // ---------- findActiveAssignments ----------

    @Test
    void findActiveAssignmentsMapsOnlyMinimalMetadata() {
        when(ticketMapper.selectActiveAssignments(TARGET_USER_ID))
                .thenReturn(List.of(row(TICKET_NO, TICKET_VERSION)));

        List<AdminHandoffAssignmentResult> assignments =
                adapter.findActiveAssignments(TARGET_USER_ID);

        assertThat(assignments).containsExactly(new AdminHandoffAssignmentResult(
                TICKET_NO, PROCESSING, TARGET_USER_ID, "演示 IT 支持人员", TICKET_VERSION));
    }

    // ---------- replaceAssignees：方案完整性 ----------

    /** 数据库里仍由目标用户负责、方案里却没有的工单：方案不完整，必须补齐而不是静默跳过。 */
    @Test
    void replaceAssigneesRejectsAssignmentMissingFromPlan() {
        when(ticketMapper.selectActiveAssignmentsForUpdate(TARGET_USER_ID))
                .thenReturn(List.of(row(TICKET_NO, TICKET_VERSION), row(OTHER_TICKET_NO, 1L)));

        assertApiException(
                () -> adapter.replaceAssignees(TARGET_USER_ID, OPERATOR_ID, REASON,
                        List.of(instruction(TICKET_NO, TICKET_VERSION, NEW_ASSIGNEE_ID)), NOW),
                "ADMIN_HANDOFF_REQUIRED");

        verify(iamUserMapper, never()).selectByIdsForUpdate(any());
        verify(ticketMapper, never()).handoffAssignee(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    /** 方案指向了不属于该用户的活动工单：不是"漏交"而是状态漂移，按冲突返回。 */
    @Test
    void replaceAssigneesRejectsTicketThatIsNotAssignedToTargetUser() {
        when(ticketMapper.selectActiveAssignmentsForUpdate(TARGET_USER_ID))
                .thenReturn(List.of(row(TICKET_NO, TICKET_VERSION)));

        assertApiException(
                () -> adapter.replaceAssignees(TARGET_USER_ID, OPERATOR_ID, REASON,
                        List.of(instruction(TICKET_NO, TICKET_VERSION, NEW_ASSIGNEE_ID),
                                instruction(OTHER_TICKET_NO, 1L, NEW_ASSIGNEE_ID)), NOW),
                "ADMIN_HANDOFF_CONFLICT");

        verify(ticketMapper, never()).handoffAssignee(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    /** 工单版本被其它动作推进过：带上当前快照，调用方刷新后重试。 */
    @Test
    void replaceAssigneesReportsStaleTicketVersionWithSnapshot() {
        when(ticketMapper.selectActiveAssignmentsForUpdate(TARGET_USER_ID))
                .thenReturn(List.of(row(TICKET_NO, TICKET_VERSION + 1)));

        assertThatThrownBy(() -> adapter.replaceAssignees(TARGET_USER_ID, OPERATOR_ID, REASON,
                List.of(instruction(TICKET_NO, TICKET_VERSION, NEW_ASSIGNEE_ID)), NOW))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("ADMIN_HANDOFF_CONFLICT");
                    assertThat(exception.resourceVersion()).isEqualTo(TICKET_VERSION + 1);
                    assertThat(exception.resourceStatus()).isEqualTo(PROCESSING);
                });
    }

    // ---------- replaceAssignees：锁顺序与候选人 ----------

    /** 固定锁顺序：先按目标用户锁工单行，再按 id 升序锁用户行（含目标用户与操作人）。 */
    @Test
    void replaceAssigneesLocksTicketRowsBeforeUserRowsInIdOrder() {
        when(ticketMapper.selectActiveAssignmentsForUpdate(TARGET_USER_ID))
                .thenReturn(List.of(row(TICKET_NO, TICKET_VERSION)));
        when(iamUserMapper.selectByIdsForUpdate(any()))
                .thenReturn(List.of(user(OPERATOR_ID), user(NEW_ASSIGNEE_ID), user(TARGET_USER_ID)));
        when(ticketClaimantPort.lockEligibleClaimant(NEW_ASSIGNEE_ID))
                .thenReturn(new TicketUserSummaryResult(NEW_ASSIGNEE_ID, "备用 IT 支持"));
        when(ticketMapper.handoffAssignee(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(ticket(RECORD_SEQ));

        adapter.replaceAssignees(TARGET_USER_ID, OPERATOR_ID, REASON,
                List.of(instruction(TICKET_NO, TICKET_VERSION, NEW_ASSIGNEE_ID)), NOW);

        InOrder order = inOrder(ticketMapper, iamUserMapper);
        order.verify(ticketMapper).selectActiveAssignmentsForUpdate(TARGET_USER_ID);
        // 操作人与目标用户也要显式加锁：ticket_record 的外键校验会申请这两行的共享锁
        order.verify(iamUserMapper).selectByIdsForUpdate(
                List.of(OPERATOR_ID, NEW_ASSIGNEE_ID, TARGET_USER_ID));
    }

    @Test
    void replaceAssigneesRejectsMissingUserRow() {
        when(ticketMapper.selectActiveAssignmentsForUpdate(TARGET_USER_ID))
                .thenReturn(List.of(row(TICKET_NO, TICKET_VERSION)));
        when(iamUserMapper.selectByIdsForUpdate(any()))
                .thenReturn(List.of(user(TARGET_USER_ID)));

        assertApiException(
                () -> adapter.replaceAssignees(TARGET_USER_ID, OPERATOR_ID, REASON,
                        List.of(instruction(TICKET_NO, TICKET_VERSION, NEW_ASSIGNEE_ID)), NOW),
                "ADMIN_HANDOFF_CONFLICT");
    }

    @Test
    void replaceAssigneesRejectsRequesterAsNewAssignee() {
        when(ticketMapper.selectActiveAssignmentsForUpdate(TARGET_USER_ID))
                .thenReturn(List.of(row(TICKET_NO, TICKET_VERSION)));
        when(iamUserMapper.selectByIdsForUpdate(any()))
                .thenReturn(List.of(user(OPERATOR_ID), user(REQUESTER_ID), user(TARGET_USER_ID)));

        assertApiException(
                () -> adapter.replaceAssignees(TARGET_USER_ID, OPERATOR_ID, REASON,
                        List.of(instruction(TICKET_NO, TICKET_VERSION, REQUESTER_ID)), NOW),
                "ADMIN_HANDOFF_CONFLICT");

        verify(ticketMapper, never()).handoffAssignee(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void replaceAssigneesRejectsOriginalAssigneeAsNewAssignee() {
        when(ticketMapper.selectActiveAssignmentsForUpdate(TARGET_USER_ID))
                .thenReturn(List.of(row(TICKET_NO, TICKET_VERSION)));
        when(iamUserMapper.selectByIdsForUpdate(any()))
                .thenReturn(List.of(user(TARGET_USER_ID)));

        assertApiException(
                () -> adapter.replaceAssignees(TARGET_USER_ID, OPERATOR_ID, REASON,
                        List.of(instruction(TICKET_NO, TICKET_VERSION, TARGET_USER_ID)), NOW),
                "ADMIN_HANDOFF_CONFLICT");
    }

    /** 候选资格在锁内复核：被停用或失去 IT_SUPPORT 的接替人必须被挡下。 */
    @Test
    void replaceAssigneesRejectsCandidateThatLostItSupport() {
        when(ticketMapper.selectActiveAssignmentsForUpdate(TARGET_USER_ID))
                .thenReturn(List.of(row(TICKET_NO, TICKET_VERSION)));
        when(iamUserMapper.selectByIdsForUpdate(any()))
                .thenReturn(List.of(user(OPERATOR_ID), user(NEW_ASSIGNEE_ID), user(TARGET_USER_ID)));
        when(ticketClaimantPort.lockEligibleClaimant(NEW_ASSIGNEE_ID)).thenReturn(null);

        assertApiException(
                () -> adapter.replaceAssignees(TARGET_USER_ID, OPERATOR_ID, REASON,
                        List.of(instruction(TICKET_NO, TICKET_VERSION, NEW_ASSIGNEE_ID)), NOW),
                "ADMIN_HANDOFF_CONFLICT");

        verify(ticketMapper, never()).handoffAssignee(anyLong(), anyLong(), anyLong(), anyLong(), any());
    }

    /** 条件更新是唯一胜者判定：命中 0 行说明锁期间的复核已不可信。 */
    @Test
    void replaceAssigneesConvertsLostConditionalUpdateToConflict() {
        when(ticketMapper.selectActiveAssignmentsForUpdate(TARGET_USER_ID))
                .thenReturn(List.of(row(TICKET_NO, TICKET_VERSION)));
        when(iamUserMapper.selectByIdsForUpdate(any()))
                .thenReturn(List.of(user(OPERATOR_ID), user(NEW_ASSIGNEE_ID), user(TARGET_USER_ID)));
        when(ticketClaimantPort.lockEligibleClaimant(NEW_ASSIGNEE_ID))
                .thenReturn(new TicketUserSummaryResult(NEW_ASSIGNEE_ID, "备用 IT 支持"));
        when(ticketMapper.handoffAssignee(anyLong(), anyLong(), anyLong(), anyLong(), any()))
                .thenReturn(0);

        assertApiException(
                () -> adapter.replaceAssignees(TARGET_USER_ID, OPERATOR_ID, REASON,
                        List.of(instruction(TICKET_NO, TICKET_VERSION, NEW_ASSIGNEE_ID)), NOW),
                "ADMIN_HANDOFF_CONFLICT");

        verify(ticketParticipantMapper, never()).recordAssignment(anyLong(), anyLong(), any());
        verify(ticketRecordMapper, never()).insert(any(TicketRecord.class));
    }

    // ---------- replaceAssignees：三处写入 ----------

    @Test
    void replaceAssigneesReplacesAssigneeAndWritesParticipantAndHandoffRecord() {
        when(ticketMapper.selectActiveAssignmentsForUpdate(TARGET_USER_ID))
                .thenReturn(List.of(row(TICKET_NO, TICKET_VERSION)));
        when(iamUserMapper.selectByIdsForUpdate(any()))
                .thenReturn(List.of(user(OPERATOR_ID), user(NEW_ASSIGNEE_ID), user(TARGET_USER_ID)));
        when(ticketClaimantPort.lockEligibleClaimant(NEW_ASSIGNEE_ID))
                .thenReturn(new TicketUserSummaryResult(NEW_ASSIGNEE_ID, "备用 IT 支持"));
        when(ticketMapper.handoffAssignee(TICKET_ID, TICKET_VERSION, TARGET_USER_ID,
                NEW_ASSIGNEE_ID, NOW)).thenReturn(1);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(ticket(RECORD_SEQ));

        adapter.replaceAssignees(TARGET_USER_ID, OPERATOR_ID, REASON,
                List.of(instruction(TICKET_NO, TICKET_VERSION, NEW_ASSIGNEE_ID)), NOW);

        // 参与关系：与领取、转交一致，换人后新负责人才看得到这张工单
        verify(ticketParticipantMapper).recordAssignment(TICKET_ID, NEW_ASSIGNEE_ID, NOW);

        // 交接记录：没有专门表，就是 ticket_record 里一条 ADMIN_HANDOFF
        ArgumentCaptor<TicketRecord> captured = ArgumentCaptor.forClass(TicketRecord.class);
        verify(ticketRecordMapper).insert(captured.capture());
        TicketRecord record = captured.getValue();
        assertThat(record.getTicketId()).isEqualTo(TICKET_ID);
        assertThat(record.getSequenceNo()).isEqualTo(RECORD_SEQ);
        assertThat(record.getRecordType()).isEqualTo("ADMIN_HANDOFF");
        assertThat(record.getActorType()).isEqualTo("USER");
        assertThat(record.getActorUserId()).isEqualTo(OPERATOR_ID);
        assertThat(record.getReason()).isEqualTo(REASON);
        assertThat(record.getFromAssigneeId()).isEqualTo(TARGET_USER_ID);
        assertThat(record.getToAssigneeId()).isEqualTo(NEW_ASSIGNEE_ID);
        // 交接不改变状态：两侧写同一个当前状态
        assertThat(record.getFromStatus()).isEqualTo(PROCESSING);
        assertThat(record.getToStatus()).isEqualTo(PROCESSING);
        assertThat(record.getCreatedAt()).isEqualTo(NOW);
    }

    // ---------- 辅助 ----------

    private static AdminHandoffTicketRow row(String ticketNo, long version) {
        AdminHandoffTicketRow row = new AdminHandoffTicketRow();
        row.setId(TICKET_ID);
        row.setTicketNo(ticketNo);
        row.setStatus(PROCESSING);
        row.setRequesterId(REQUESTER_ID);
        row.setAssigneeId(TARGET_USER_ID);
        row.setAssigneeDisplayName("演示 IT 支持人员");
        row.setVersion(version);
        return row;
    }

    private static AdminHandoffInstruction instruction(
            String ticketNo, long ticketVersion, long newAssigneeId) {
        return new AdminHandoffInstruction(ticketNo, ticketVersion, newAssigneeId);
    }

    private static Ticket ticket(int recordSeq) {
        Ticket ticket = new Ticket();
        ticket.setId(TICKET_ID);
        ticket.setTicketNo(TICKET_NO);
        ticket.setStatus(PROCESSING);
        ticket.setRecordSeq(recordSeq);
        ticket.setVersion(TICKET_VERSION + 1);
        return ticket;
    }

    private static IamUser user(long id) {
        IamUser user = new IamUser();
        user.setId(id);
        user.setUsername("user-" + id);
        user.setDisplayName("用户 " + id);
        user.setStatus(IamUserStatus.ENABLED);
        user.setVersion(0L);
        return user;
    }

    private static void assertApiException(ThrowingCallable action, String code) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo(code);
                });
    }
}
