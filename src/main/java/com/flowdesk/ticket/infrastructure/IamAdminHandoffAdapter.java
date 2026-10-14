package com.flowdesk.ticket.infrastructure;

import com.flowdesk.common.exception.ApiException;
import com.flowdesk.iam.application.command.AdminHandoffInstruction;
import com.flowdesk.iam.application.port.AdminHandoffPort;
import com.flowdesk.iam.application.result.AdminHandoffAssignmentResult;
import com.flowdesk.iam.domain.IamUser;
import com.flowdesk.iam.mapper.IamUserMapper;
import com.flowdesk.ticket.application.port.TicketClaimantPort;
import com.flowdesk.ticket.domain.Ticket;
import com.flowdesk.ticket.domain.TicketRecord;
import com.flowdesk.ticket.domain.TicketRecordType;
import com.flowdesk.ticket.infrastructure.persistence.AdminHandoffTicketRow;
import com.flowdesk.ticket.mapper.TicketMapper;
import com.flowdesk.ticket.mapper.TicketParticipantMapper;
import com.flowdesk.ticket.mapper.TicketRecordMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code AdminHandoffPort} 的工单模块实现（{@code docs/api-design.md} 8.3）。
 *
 * <p>它把"管理性交接"这件事落在工单侧：锁定目标用户的活动工单、复核方案与候选人、
 * 替换负责人、补参与关系、写一条 {@code ADMIN_HANDOFF} 记录。没有专门的交接记录表——
 * 记录就是 {@code ticket_record} 里的一条，{@code reason} 承载 {@code handoffReason}。</p>
 *
 * <p><strong>事务</strong>：本类不开事务，全部语句加入调用方（IAM 的 {@code disable} /
 * {@code replaceRoles}）已经开启的那个事务，因此"账号/角色变更 + 全部负责人替换 + 参与关系 +
 * 交接记录"共同成功或共同失败。</p>
 *
 * <p><strong>锁顺序</strong>：工单行（{@link TicketMapper#selectActiveAssignmentsForUpdate}，
 * 按 id 升序）→ 用户行（{@link IamUserMapper#selectByIdsForUpdate}，按 id 升序）→ 参与关系与记录。
 * 目标用户与操作人也一并锁住：写 {@code ticket_record} 时 {@code from_assignee_id} /
 * {@code actor_user_id} 的外键校验会申请这两行的共享锁，显式先锁才能保持顺序可预测。</p>
 */
@Component
public class IamAdminHandoffAdapter implements AdminHandoffPort {

    private final TicketMapper ticketMapper;
    private final TicketParticipantMapper ticketParticipantMapper;
    private final TicketRecordMapper ticketRecordMapper;
    private final IamUserMapper iamUserMapper;
    private final TicketClaimantPort ticketClaimantPort;

    public IamAdminHandoffAdapter(
            TicketMapper ticketMapper,
            TicketParticipantMapper ticketParticipantMapper,
            TicketRecordMapper ticketRecordMapper,
            IamUserMapper iamUserMapper,
            TicketClaimantPort ticketClaimantPort) {
        this.ticketMapper = ticketMapper;
        this.ticketParticipantMapper = ticketParticipantMapper;
        this.ticketRecordMapper = ticketRecordMapper;
        this.iamUserMapper = iamUserMapper;
        this.ticketClaimantPort = ticketClaimantPort;
    }

    @Override
    public List<AdminHandoffAssignmentResult> findActiveAssignments(long userId) {
        return ticketMapper.selectActiveAssignments(userId).stream()
                .map(row -> new AdminHandoffAssignmentResult(
                        row.getTicketNo(),
                        row.getStatus(),
                        row.getAssigneeId(),
                        row.getAssigneeDisplayName(),
                        row.getVersion()))
                .toList();
    }

    @Override
    public void replaceAssignees(long targetUserId, long operatorId, String reason,
            List<AdminHandoffInstruction> handoffs, LocalDateTime now) {

        /*
         * 1.权威集合：锁定目标用户当前负责的全部活动工单，按 id 升序。
         *   请求里的 ticketNo 不作为加锁入口——先信请求再核对，会把"漏交"变成静默放行。
         */
        List<AdminHandoffTicketRow> locked = ticketMapper.selectActiveAssignmentsForUpdate(targetUserId);

        Map<String, AdminHandoffInstruction> plan = new LinkedHashMap<>();
        for (AdminHandoffInstruction instruction : handoffs) {
            plan.put(instruction.ticketNo(), instruction);
        }

        Map<String, AdminHandoffTicketRow> lockedByTicketNo = new LinkedHashMap<>();
        for (AdminHandoffTicketRow row : locked) {
            lockedByTicketNo.put(row.getTicketNo(), row);
        }

        //2.漏交：仍由目标用户负责、方案里却没有的工单 → 方案不完整
        for (AdminHandoffTicketRow row : locked) {
            if (!plan.containsKey(row.getTicketNo())) {
                throw handoffRequired("交接方案缺少工单 " + row.getTicketNo());
            }
        }

        //3.多交与版本：方案指向了不属于该用户的活动工单，或工单已被其它动作推进
        for (AdminHandoffInstruction instruction : plan.values()) {
            AdminHandoffTicketRow row = lockedByTicketNo.get(instruction.ticketNo());
            if (row == null) {
                throw handoffConflict(
                        "工单 " + instruction.ticketNo() + " 不在该用户当前负责的活动工单内");
            }
            if (!instruction.ticketVersion().equals(row.getVersion())) {
                throw handoffConflict("工单 " + row.getTicketNo() + " 的版本已变化",
                        row.getVersion(), row.getStatus());
            }
        }

        //4.用户行按 id 升序加锁（必须在工单行之后）：目标用户 + 全部新负责人 + 操作人
        List<Long> userIds = userLockIds(targetUserId, operatorId, plan.values());
        List<IamUser> lockedUsers = iamUserMapper.selectByIdsForUpdate(userIds);
        if (lockedUsers.size() != userIds.size()) {
            throw handoffConflict("交接涉及的用户已不存在");
        }

        //5.逐张复核候选资格并换人；工单表、参与关系、交接记录三处写入都在调用方事务里
        for (AdminHandoffTicketRow row : locked) {
            AdminHandoffInstruction instruction = plan.get(row.getTicketNo());
            long newAssigneeId = instruction.newAssigneeId();

            if (newAssigneeId == targetUserId) {
                throw handoffConflict("工单 " + row.getTicketNo() + " 的接替人不能是原负责人");
            }
            if (newAssigneeId == row.getRequesterId()) {
                throw handoffConflict("工单 " + row.getTicketNo() + " 的接替人不能是提交人");
            }
            //与领取、转交共用同一条资格判定：启用 + 持有 IT_SUPPORT
            if (ticketClaimantPort.lockEligibleClaimant(newAssigneeId) == null) {
                throw handoffConflict("接替人必须是启用且持有 IT_SUPPORT 的用户");
            }

            int updatedRows = ticketMapper.handoffAssignee(
                    row.getId(),
                    instruction.ticketVersion(),
                    targetUserId,
                    newAssigneeId,
                    now);

            if (updatedRows != 1) {
                throw handoffConflict("工单 " + row.getTicketNo() + " 的负责人或状态已变化");
            }

            Ticket updated = ticketMapper.selectById(row.getId());
            if (updated == null || updated.getRecordSeq() == null) {
                throw new IllegalStateException("交接后无法读取工单快照");
            }

            //新负责人的参与关系：与领取、转交一致，换人后他才看得到这张工单
            ticketParticipantMapper.recordAssignment(row.getId(), newAssigneeId, now);

            /*
             * 不可变时间线：管理员操作、原负责人 → 新负责人、原因由 handoffReason 承担。
             * 状态没变，两侧写同一个状态——交接不是状态迁移。
             */
            TicketRecord record = new TicketRecord();
            record.setTicketId(row.getId());
            record.setSequenceNo(updated.getRecordSeq());
            record.setRecordType(TicketRecordType.ADMIN_HANDOFF);
            record.setActorType("USER");
            record.setActorUserId(operatorId);
            record.setReason(reason);
            record.setFromAssigneeId(targetUserId);
            record.setToAssigneeId(newAssigneeId);
            record.setFromStatus(row.getStatus());
            record.setToStatus(row.getStatus());
            record.setCreatedAt(now);

            if (ticketRecordMapper.insert(record) != 1) {
                throw new IllegalStateException("交接记录插入失败");
            }
        }
    }

    /** 需要加锁的用户集合：目标用户、操作人与全部新负责人，去重后按 id 升序。 */
    private List<Long> userLockIds(long targetUserId, long operatorId,
            Collection<AdminHandoffInstruction> instructions) {
        return Stream.concat(
                        Stream.of(targetUserId, operatorId),
                        instructions.stream().map(AdminHandoffInstruction::newAssigneeId))
                .distinct()
                .sorted()
                .toList();
    }

    private ApiException handoffRequired(String message) {
        return new ApiException(HttpStatus.CONFLICT, "ADMIN_HANDOFF_REQUIRED", message);
    }

    private ApiException handoffConflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "ADMIN_HANDOFF_CONFLICT", message);
    }

    /** 冲突时附带工单当前快照（版本与状态），与 {@code 409/TICKET_CONFLICT} 同一口径。 */
    private ApiException handoffConflict(String message, Long version, String status) {
        return new ApiException(
                HttpStatus.CONFLICT, "ADMIN_HANDOFF_CONFLICT", message, version, status);
    }
}
