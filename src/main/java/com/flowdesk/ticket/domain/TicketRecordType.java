package com.flowdesk.ticket.domain;

/**
 * {@code ticket_record.record_type} 的取值常量。
 *
 * <p>这里只登记本切片（管理性交接）真正写入的取值；完整白名单以
 * {@code V8__add_cancel_request_expiry.sql} 重建的 {@code ck_ticket_record_type} 为准，
 * 不在此重复一遍，避免与已发布迁移形成第二个真相来源。既有代码仍按字面量写记录类型
 * （如 {@code TicketServiceImpl} 的 {@code "TRANSFER"}），本切片不做顺带重构。</p>
 */
public final class TicketRecordType {

    /**
     * 管理性交接：管理员因停用账号或移除 {@code IT_SUPPORT} 角色替换工单负责人。
     *
     * <p>没有专门的交接记录表——「交接记录」就是往 {@code ticket_record} 写这一条：
     * {@code actor_user_id} 是管理员、{@code from_assignee_id} 是原负责人、
     * {@code to_assignee_id} 是新负责人、{@code reason} 承载 {@code handoffReason}、
     * 前后状态相同（交接不改变状态）。</p>
     */
    public static final String ADMIN_HANDOFF = "ADMIN_HANDOFF";

    private TicketRecordType() {
    }
}
