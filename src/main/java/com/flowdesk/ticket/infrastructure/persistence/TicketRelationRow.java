package com.flowdesk.ticket.infrastructure.persistence;

import lombok.Data;

/**
 * 工单关联查询的扁平投影：一条关联的编码 + 相对方向 + 对端工单摘要。
 *
 * <p>方向由 SQL 按「本单是 source 还是 target」生成（{@code OUTGOING} / {@code INCOMING}），
 * 服务层不需要再推方向。只取识别与展示需要的列：对端工单的编号、标题、状态；
 * 不读取对端正文、分类与负责人。</p>
 */
@Data
public class TicketRelationRow {
    private String relationType;
    private String direction;
    private String ticketNo;
    private String title;
    private String status;
}