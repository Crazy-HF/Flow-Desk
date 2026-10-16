package com.flowdesk.ticket.infrastructure.persistence;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 超时扫描命中的待处置工单：只取条件更新与留痕需要的三列。
 *
 * <p>三个字段都取自扫描那一刻的快照，因此都可能在提交更新前失效——真正的判定在
 * {@code TicketMapper} 的条件更新里（`version` + 等待态 + 期限已到），命中 0 行即静默跳过。</p>
 */
@Data
public class TicketTimeoutRow {
    private Long id;
    private Long version;
    /** 进入等待态时写入的期限；自动关闭要把它原样写进留痕，让人能读到当初的截止时间。 */
    private LocalDateTime actionDeadlineAt;
}
