package com.flowdesk.ticket.infrastructure;

import com.flowdesk.ticket.application.service.TicketService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 工单超时自动任务
 * 每轮按固定次序做四件事——
 * ① 期限进入提前量窗口的工单发一次到期前提醒，不改状态；
 * ② 「待确认」到期视为提交人默许验收，自动完成；
 * ③ 「待补充」到期视为提交人未响应，自动关闭；
 * ④ 回收「撤销请求已过期」工单上残留的到期前提醒。
 *
 * ②③ 的补处理不设时间上限：停机期间到期的工单在恢复后仍满足「期限已到且仍在等待态」，
 * 会被后续轮次继续选中处置，不需要为"迟到多久"设一档门槛。
 * ④ 是唯一带窗口的：撤销请求过期后三个字段不会清空（到期不产生业务动作），
 * 所以只回收「过期不超过一天」的行，停机超过一天的漏网回落给站内信清理任务兜底。
 *
 * <p>四段相互独立、各自兜住异常：一段出现系统性失败不影响其余，也不会把异常抛回调度器；
 * 逐条失败的处理与重试口径由 ServiceImpl 按条负责。</p>
 *
 * <p>v1 单实例且调度线程池只有一条线程，同一轮不会与上一轮重叠，不需要分布式锁；
 * 幂等由「条件更新命中 1 行」与「按工单 + 类别删除」保证——已被人工动作抢先的工单更新 0 行、
 * 提醒早已不在的工单删除 0 行，都静默跳过。</p>
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

    /**
     * 一轮扫描：先发到期前提醒，再处置到期的【待确认】/【待补充】，最后回收过期撤销请求残留的提醒
     */
    @Scheduled(cron = CRON)
    public void scanAndDispose() {
        remindDueSoon();
        disposeExpiredConfirmations();
        disposeExpiredSupplements();
        recallExpiredCancelReminders();
    }

    /** 到期前提醒：确认 / 补充 / 撤销请求期限进入提前量窗口的工单各提醒一次，不改状态 */
    private void remindDueSoon() {
        try {
            int reminded = ticketService.publishDueSoonReminders(BATCH_SIZE);
            if (reminded > 0) {
                log.info("Published {} due-soon reminders", reminded);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to publish due-soon reminders", e);
        }
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

    /**
     * 回收「撤销请求已过期」工单上残留的到期前提醒
     * 撤销请求到期不产生业务动作，没有业务入口回收它的提醒，只能由这里兜底；
     * 命中即删、提醒已不在就是删 0 行，两种结果都不影响工单状态与时间线。
     */
    private void recallExpiredCancelReminders() {
        try {
            int recalled = ticketService.recallExpiredCancelReminders(BATCH_SIZE);
            if (recalled > 0) {
                log.info("Recalled stale cancel-request reminders for {} tickets", recalled);
            }
        } catch (RuntimeException e) {
            log.warn("Failed to recall stale cancel-request reminders", e);
        }
    }
}
