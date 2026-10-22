package com.flowdesk.ticket.application.result;

/** 分类分组：当前分类的标识与名称（停用分类同样返回它的当前名称）+ 数量。 */
public record TicketDashboardCategoryCountResult(
        TicketCategorySummaryResult category,
        long count) {
}
