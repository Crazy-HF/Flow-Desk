package com.flowdesk.ticket.domain;

public enum TicketStatus {
    /** 待处理 */
    PENDING,
    /** 处理中 */
    PROCESSING,
    /** 待请求者处理 */
    WAITING_FOR_REQUESTER,
    /** 待确认 */
    WAITING_FOR_CONFIRMATION,
    /** 完成 */
    COMPLETED,
    /** 取消 */
    CANCELED,
    /** 关闭 */
    CLOSED;

    /**
     * 是否「有人负责且未终结」——管理性交接必须覆盖的全部状态。
     *
     * <p>三个状态都留着一名当前负责人，也都可能还需要他继续动作：处理中要推进、待补充要收补充、
     * 待确认要接提交人的确认或未解决反馈。因此停用账号或摘掉 {@code IT_SUPPORT} 之前，
     * 这三个状态上的工单一张都不能漏交（{@code docs/api-design.md} 8.3）。</p>
     *
     * <p>{@code PENDING} 不属于这里：它没有负责人，交接无从谈起；三个终态也不属于这里：
     * 负责人字段只是历史信息。SQL 侧不能调用本方法，白名单在 {@code TicketMapper} 的语句里重复一次，
     * 两边必须同时改。</p>
     */
    public boolean isActiveAssignment() {
        return this == PROCESSING
                || this == WAITING_FOR_REQUESTER
                || this == WAITING_FOR_CONFIRMATION;
    }
}
