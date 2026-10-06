package com.flowdesk.ticket.application.port;

import com.flowdesk.ticket.application.result.TicketUserSummaryResult;

public interface TicketClaimantPort {
    /** 详情页按钮提示使用；不加锁，执行动作时仍须重新校验。 */
    boolean isEligibleClaimant(long userId);

    /** 当前仍启用且拥有 IT_SUPPORT 角色时返回用户摘要，否则返回 null。调用方须在事务内使用。 */
    TicketUserSummaryResult lockEligibleClaimant(long userId);
}
