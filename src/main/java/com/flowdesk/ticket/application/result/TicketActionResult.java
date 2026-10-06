package com.flowdesk.ticket.application.result;

import java.time.OffsetDateTime;

/**
 * 工单动作的统一成功结果（`docs/api-design.md` 6.2：所有已有工单写操作返回最新快照摘要）。
 *
 * <p>字段含义：最新状态、当前或最后负责人摘要、当前有效期限（只有待补充与待确认有值，
 * 其他状态为 {@code null} 且不参与 JSON 序列化）、递增后的版本号与动作发生时间。</p>
 */
public record TicketActionResult(
        String ticketNo,
        String status,
        TicketUserSummaryResult assignee,
        OffsetDateTime actionDeadlineAt,
        Long version,
        OffsetDateTime actionTime
) {
}
