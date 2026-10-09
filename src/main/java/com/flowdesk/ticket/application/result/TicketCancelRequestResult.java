package com.flowdesk.ticket.application.result;

import java.time.OffsetDateTime;

/**
 * 待批准的撤销请求（{@code docs/kickoff.md} 4.7 未来方向）。
 *
 * <p>请求存在期间工单状态不变，所以它无法从 {@code status} 推出来，必须单独暴露：
 * 详情不返回这份数据，界面就渲染不出「IT 待批准」提示与那三个决策按钮。</p>
 *
 * <p>{@code deadlineAt} 只用于展示与提醒，到期不会自动改变工单状态——
 * 与补充期限、确认期限同口径（用户 2026-10-06 裁决：本版本没有定时任务）。</p>
 */
public record TicketCancelRequestResult(
        OffsetDateTime requestedAt,
        OffsetDateTime deadlineAt,
        String reason) {
}
