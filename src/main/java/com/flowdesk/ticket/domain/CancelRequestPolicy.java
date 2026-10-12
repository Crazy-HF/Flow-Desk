package com.flowdesk.ticket.domain;

import java.time.LocalDateTime;

/**
 * 待批准撤销请求的期限判定（{@code docs/kickoff.md} 4.7、{@code docs/implementation-plan.md} 9.3 待改清单 ①④）。
 *
 * <p>「请求是否已过期」是同一条业务判定的多个出口：批准 / 拒绝 / 撤回三个动作是否还开放、提交人能不能
 * 直接重新发起（覆盖已过期的那一份）、详情返回的 {@code cancelRequest.expired}、以及 IT 列表
 * 「待我批准」筛选是否命中。因此 Java 侧**只有这一处实现**，由 {@code TicketServiceImpl} 与
 * {@code TicketQueryServiceImpl} 共同调用。</p>
 *
 * <p><b>SQL 侧无法共用代码</b>：四条条件更新的 {@code WHERE} 与列表筛选的 SQL 各写一遍期限条件，
 * 与这里互为反面（SQL 用 {@code cancel_request_deadline_at > #{now}} 表示「未过期」，本类用
 * {@code <= now} 表示「已过期」）。两边靠注释交叉引用，并由 {@code TicketQueryServiceIT} 的
 * 「详情两格 ⟺ 列表命中」交叉验证用例钉住——不允许出现「列表说可批准、点下去已过期」的分叉。</p>
 *
 * <p>判定用的 {@code now} 一律来自应用注入的 {@code Clock}（UTC），与四条条件更新写库时用的
 * {@code #{now}} 同源；不引入数据库时钟作为第二个真相来源。</p>
 */
public final class CancelRequestPolicy {

    private CancelRequestPolicy() {
    }

    /**
     * 期限已到（含正好到期）。{@code deadlineAt} 为空表示这张工单上没有待决请求，不算过期。
     *
     * <p>与 SQL 里的 {@code cancel_request_deadline_at > #{now}} 严格互为反面：一边用
     * {@code !isAfter}（即 {@code <=}），另一边用 {@code >}，不存在两边都判「既没过期也不可批准」的缝。</p>
     */
    public static boolean isExpired(LocalDateTime deadlineAt, LocalDateTime now) {
        return deadlineAt != null && !deadlineAt.isAfter(now);
    }

    /**
     * 未过期的待决请求会挡住新请求——同一时刻只允许一个有效请求，重复发起按 409 处理；
     * 请求一旦过期就不再挡，新请求直接覆盖它（不设终身次数上限）。
     */
    public static boolean blocksNewRequest(LocalDateTime deadlineAt, LocalDateTime now) {
        return deadlineAt != null && deadlineAt.isAfter(now);
    }
}
