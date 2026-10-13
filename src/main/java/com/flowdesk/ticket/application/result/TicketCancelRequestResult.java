package com.flowdesk.ticket.application.result;

import java.time.OffsetDateTime;

/**
 * 待批准的撤销请求（{@code docs/kickoff.md} 4.7、{@code docs/implementation-plan.md} 9.3 决策记录）。
 *
 * <p>请求存在期间工单状态不变，所以它无法从 {@code status} 推出来，必须单独暴露：
 * 详情不返回这份数据，界面就渲染不出「IT 待批准」提示与那三个决策按钮。</p>
 *
 * <p>{@code deadlineAt} 是判定的一部分（2026-10-10 裁决，本版已实现）：期限届满后请求即失效，
 * 批准 / 拒绝 / 撤回三个动作一起关闭，提交人可以重新发起并覆盖它（工单状态仍不自动改变）。</p>
 *
 * <p>{@code expired} 由**服务端**判定（{@link com.flowdesk.ticket.domain.CancelRequestPolicy}），
 * 不让前端拿本地时钟去比 {@code deadlineAt}：两侧时钟有偏差时界面与判定会分叉，
 * 而这条判定的口径必须与批准 / 拒绝入口、IT 列表「待我批准」筛选完全一致。</p>
 */
public record TicketCancelRequestResult(
        OffsetDateTime requestedAt,
        OffsetDateTime deadlineAt,
        String reason,
        boolean expired) {
}
