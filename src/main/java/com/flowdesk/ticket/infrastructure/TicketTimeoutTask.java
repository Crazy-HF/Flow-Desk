package com.flowdesk.ticket.infrastructure;

import com.flowdesk.ticket.application.service.TicketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 工单超时自动任务
 * 每轮把两个等待态里期限已过的工单各处置一批——
 * 「待确认」到期视为提交人默许验收，自动完成；
 * 「待补充」到期视为提交人未响应，自动关闭。
 *
 * 补处理不设时间上限：停机期间到期的工单在恢复后仍满足「期限已到且仍在等待态」，
 * 会被后续轮次继续选中处置，不需要为"迟到多久"设一档门槛。
 *
 * <p>两段相互独立、各自兜住异常：一段出现系统性失败不影响另一段，也不会把异常抛回调度器；
 * 逐条失败的处理与重试口径由 ServiceImpl 按条负责。</p>
 *
 * <p>v1 单实例且调度线程池只有一条线程，同一轮不会与上一轮重叠，不需要分布式锁；
 * 幂等由「状态 + 期限已到 + 版本」的条件更新保证——已被人工动作抢先的工单更新 0 行，静默跳过。</p>
 *
 * <p>可用 {@code flowdesk.ticket.timeout-task-enabled=false} 关闭（测试与本地调试）；
 * 扫描节奏由 {@code flowdesk.ticket.timeout-scan-cron} 覆盖，默认每 5 分钟一轮。</p>
 */
@Component
@ConditionalOnProperty(name = "flowdesk.ticket.timeout-task-enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class TicketTimeoutTask {
    /**扫描：默认5分钟*/
    static final String CRON = "${flowdesk.ticket.timeout-scan-cron:0 */5 * * * *}";

    /** 单轮上限：一轮最多处置这么多张，积压交给后续轮次。 */
    static final int BATCH_SIZE = 200;

    private final TicketService ticketService;

    public TicketTimeoutTask(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /** 一轮扫描：先处置到期的【待确认】，再处置到期的【待补充】 */
    @Scheduled(cron = CRON)
    public void scanAndDispose() {
        disposeExpiredConfirmations();
        disposeExpiredSupplements();
    }

    /** 处置到期的【待确认】-自动完成 */
    private void disposeExpiredConfirmations() {
        try {
            int completed = ticketService.completeExpiredConfirmations(BATCH_SIZE);
            if (completed > 0) {
                log.info("Completed {} expired confirmations", completed);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to dispose expired confirmations", e);
        }
    }

    /** 处置到期的【待补充】-自动关闭 */
    private void disposeExpiredSupplements() {
        try {
            int closed = ticketService.closeExpiredSupplements(BATCH_SIZE);
            if (closed > 0) {
                log.info("Closed {} expired supplements", closed);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to dispose expired supplements", e);
        }
    }
}
