package com.flowdesk.ticket.domain;

public enum TicketScope {
    /** 我请求的 */
    REQUESTED_BY_ME,
    /** 待处理队列 */
    PENDING_QUEUE,
    /** 赋予我的 */
    ASSIGNED_TO_ME,
    /** 参与的 */
    PARTICIPATED_BY_ME
}
