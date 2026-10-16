package com.flowdesk.iam.application.result;

/**
 * 待交接工单的最小元数据（{@code docs/api-design.md} 8.3）。
 *
 * <p>这是管理员在管理性交接里能看到工单的<strong>全部</strong>信息：编号、状态、当前负责人和版本。
 * 刻意不包含标题、问题描述、处理记录与附件——管理员默认没有工单正文访问权，
 * 交接只解决「谁负责」，不解决「内容是什么」。</p>
 *
 * <p>字段是平的（{@code assigneeId} / {@code assigneeDisplayName}），不嵌套工单模块的
 * {@code TicketUserSummaryResult}：本类型属于 IAM 的应用层出口，它由
 * {@code iam.application.port.AdminHandoffPort} 的提供方（工单模块）填充，
 * 不能反过来让 IAM 依赖工单模块的结果类型。</p>
 *
 * <p>{@code version} 是工单乐观锁，客户端把它原样放进 {@code handoffs[].ticketVersion}；
 * 交接期间只要它变了，服务端返回 {@code 409/ADMIN_HANDOFF_CONFLICT}。</p>
 */
public record AdminHandoffAssignmentResult(
        String ticketNo,
        String status,
        Long assigneeId,
        String assigneeDisplayName,
        Long version
) {
}
