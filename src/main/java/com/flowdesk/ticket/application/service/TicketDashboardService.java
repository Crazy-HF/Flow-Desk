package com.flowdesk.ticket.application.service;

import com.flowdesk.ticket.application.query.TicketDashboardQuery;
import com.flowdesk.ticket.application.result.TicketDashboardResult;

/** 数据概览查询（{@code docs/api-design.md} 8.5）。 */
public interface TicketDashboardService {

    /**
     * 按单个 IT 可见范围统计当前工单行的总数与四类分组。
     *
     * @throws com.flowdesk.common.exception.ApiException {@code 400/VALIDATION_FAILED} 范围缺失、
     *         使用 {@code REQUESTED_BY_ME}、分类非正数或时间区间反向；
     *         {@code 403/ACCESS_DENIED} 缺少 {@code DASHBOARD_VIEW}、当前不持有 {@code IT_SUPPORT}
     *         或缺少选中范围的查看权限
     */
    TicketDashboardResult overview(TicketDashboardQuery query);
}
