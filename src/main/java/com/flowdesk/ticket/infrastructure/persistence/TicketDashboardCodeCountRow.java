package com.flowdesk.ticket.infrastructure.persistence;

import lombok.Data;

/**
 * 按编码分组的聚合投影（状态、优先级）。
 *
 * <p>只带编码与数量：中文名属于展示映射，留在前端 {@code constants/tickets.ts}，
 * 后端不复制一份迟早会漂移的对照表。</p>
 */
@Data
public class TicketDashboardCodeCountRow {
    private String code;
    private Long total;
}
