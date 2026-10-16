package com.flowdesk.ticket.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 到期前提醒的候选行：扫描与发布前的重读共用一张投影。
 *
 * <p>取哪几列由收件人规则决定——确认 / 补充提醒只发给提交人，撤销请求提醒只发给当前负责人；
 * 合成一张投影是因为重读要拿到与扫描完全一致的字段，多一张投影就多一处漏字段的机会。</p>
 */
@Data
public class TicketReminderRow {
    private Long id;
    private String ticketNo;
    private Long requesterId;
    private Long assigneeId;
    /** 命中提醒窗口的期限：三类候选各取自己那一列。 */
    private LocalDateTime deadlineAt;
}