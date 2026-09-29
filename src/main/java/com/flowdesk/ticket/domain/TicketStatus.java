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
    CLOSED
}
