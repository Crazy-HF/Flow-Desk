package com.flowdesk.iam.application.port;

import com.flowdesk.iam.application.command.AdminHandoffInstruction;
import com.flowdesk.iam.application.result.AdminHandoffAssignmentResult;

import java.time.LocalDateTime;
import java.util.List;

/**
 * IAM 在停用账号或摘除 {@code IT_SUPPORT} 时需要的工单侧能力（{@code docs/api-design.md} 8.3）。
 *
 * <p><strong>端口位置</strong>：接口由需要该能力的一侧（IAM）声明，实现放在工单模块的
 * {@code infrastructure}，与 {@code iam.application.port.SessionRevocationPort} +
 * {@code auth.infrastructure.IamSessionRevocationAdapter} 同一形状。这样依赖仍然单向
 * （工单模块本来就依赖 IAM），不会出现 {@code iam → ticket} 与 {@code ticket → iam} 互指。</p>
 *
 * <p><strong>锁顺序（实现必须遵守）</strong>：{@code ticket(id 升序 FOR UPDATE)} →
 * {@code iam_user(id 升序 FOR UPDATE)}。调用方是 IAM，它在调用 {@link #replaceAssignees} 之前
 * 只持有角色行锁，目标用户行锁必须留到交接之后再加——反过来先锁用户再去锁工单，会与
 * {@code TicketServiceImpl#transfer} 注释里描述的成环场景（工单行锁 + 外键共享锁）撞成死锁。</p>
 *
 * <p><strong>错误契约</strong>：实现直接抛出 {@code ApiException}，编码与 HTTP 状态由本端口固定：</p>
 * <ul>
 *   <li>{@code 409/ADMIN_HANDOFF_REQUIRED}：方案漏掉了目标用户仍负责的活动工单；</li>
 *   <li>{@code 409/ADMIN_HANDOFF_CONFLICT}：方案里多出不属于该用户的活动工单、工单版本不符、
 *       负责人或状态已变化、接替人不再是有效的启用 IT 用户、条件更新命中 0 行。</li>
 * </ul>
 */
public interface AdminHandoffPort {

    /**
     * 目标用户当前负责的全部活动工单最小元数据，按工单编号升序。
     *
     * <p>只读、<strong>不加锁</strong>：结果用的是本事务的一致性快照，仅用于组织方案与给出
     * "缺方案"的早期反馈；真正的判定在 {@link #replaceAssignees} 内加锁后重做。</p>
     */
    List<AdminHandoffAssignmentResult> findActiveAssignments(long userId);

    /**
     * 权威交接，加入调用方当前事务：锁工单 → 复核 → 按 id 升序锁用户 → 复核候选资格 →
     * 替换负责人 → 补参与关系 → 写 {@code ADMIN_HANDOFF} 记录。
     *
     * <p>不改变工单状态、补充期限、确认期限与撤销请求三列；只推进 {@code version} 与
     * {@code record_seq} 各一次。工单表与 {@code ticket_record} 的写入都在调用方事务里，
     * 因此账号/角色变更与全部工单换人共同成功或共同失败。</p>
     *
     * @param targetUserId 原负责人（被停用或失去 IT 支持能力的用户）
     * @param operatorId   执行交接的管理员，写进记录的 {@code actor_user_id}
     * @param reason       交接原因，写进每张工单记录的 {@code reason}
     * @param handoffs     每张活动工单的交接方案；服务端要求它与目标用户的活动工单集合完全一致
     * @param now          与本次事务同一时刻的时间戳（工单时间线与记录时间共用）
     */
    void replaceAssignees(long targetUserId, long operatorId, String reason,
                          List<AdminHandoffInstruction> handoffs, LocalDateTime now);
}
