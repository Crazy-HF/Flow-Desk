package com.flowdesk.ticket.infrastructure.persistence;

import lombok.Data;

/**
 * 管理性交接所需的最小工单元数据投影（{@code docs/api-design.md} 8.3）。
 *
 * <p>投影而不是实体：这个形状同时服务两处，因此刻意不包含标题、问题描述、分类、期限等正文相关列——</p>
 *
 * <ul>
 *   <li>{@code AdminHandoffPort.findActiveAssignments}：按 {@code assignee_id} + 三个活动状态查出
 *       {@code assigneeDisplayName}，转成 {@code AdminHandoffAssignmentResult} 交给 IAM 校验方案完整性；</li>
 *   <li>交接写入前的加锁复核：按 {@code ticket_no} 集合 {@code FOR UPDATE} 读回工单行，
 *       复核「负责人仍是目标用户、状态仍是活动状态、版本仍等于请求里的 {@code ticketVersion}」。</li>
 * </ul>
 *
 * <p><strong>加锁用的那条 SQL 不要 JOIN {@code iam_user}</strong>：MySQL 的
 * {@code SELECT ... FOR UPDATE} 会锁住连接里读到的行，带上 JOIN 就会在拿到工单行锁之前先锁住
 * {@code iam_user}，把「工单行 → 用户行」的固定锁顺序变成反向，正是
 * {@code TicketServiceImpl#transfer} 注释里描述的成环场景。显示名称只在无锁的读取语句里查。</p>
 */
@Data
public class AdminHandoffTicketRow {
    private Long id;
    private String ticketNo;
    private String status;
    private Long requesterId;
    private Long assigneeId;
    private String assigneeDisplayName;
    private Long version;
}
