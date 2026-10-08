package com.flowdesk.ticket.infrastructure.persistence;

import lombok.Data;

/**
 * 关闭为「重复工单」时解析出的目标工单投影。
 *
 * <p>只取定位与判定所需的列：{@code id} 用于写 {@code ticket_relation} 与错误时的说明，
 * {@code ticketNo} 与 {@code status} 用于服务层的口径判定。工单模块不读取目标的标题或正文——
 * 关闭方只需要知道"另一张单存在且有效"，不需要把对方内容抄进自己的记录。</p>
 */
@Data
public class TicketDuplicateTargetRow {
    private Long id;
    private String ticketNo;
    private String status;
}
