package com.flowdesk.ticket.infrastructure.persistence;

import lombok.Data;

/**
 * 按关联行分组的聚合投影（分类、负责人）。
 *
 * <p>{@code refId} 与 {@code displayName} 都可为 {@code null}：负责人分组的"未分配"是
 * {@code assignee_id IS NULL} 的那一组。分类分组不会出现空值（工单的分类列非空且有外键）。</p>
 */
@Data
public class TicketDashboardRefCountRow {
    private Long refId;
    private String displayName;
    private Long total;
}
