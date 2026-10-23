package com.flowdesk.ticket.infrastructure;

/**
 * @ClassName TicketAiRetentionTask
 * @Description TODO
 * @Author xb.m
 * @Date 2026/10/23 23:07
 * @Version 1.0
 */

import com.flowdesk.ticket.mapper.TicketAiRequestMapper;
import com.flowdesk.ticket.mapper.TicketAiSuggestionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;

/**
 * 清理结束满 30 天的工单 AI 历史及对应请求元数据。
 * AI 功能关闭时仍需运行，以兑现已经保存的数据的保留期限。
 */
@Component
@Slf4j
public class TicketAiRetentionTask {

    private static final int BATCH_SIZE = 200;

    private final TicketAiRequestMapper requestMapper;
    private final TicketAiSuggestionMapper suggestionMapper;
    private final Clock clock;
    private final TransactionTemplate batchTransaction;

    public TicketAiRetentionTask(
            TicketAiRequestMapper requestMapper,
            TicketAiSuggestionMapper suggestionMapper,
            Clock clock,
            PlatformTransactionManager transactionManager) {
        this.requestMapper = requestMapper;
        this.suggestionMapper = suggestionMapper;
        this.clock = clock;

        this.batchTransaction = new TransactionTemplate(transactionManager);
        this.batchTransaction.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 每天北京时间 06:00 执行；判定时间只取一次，
     * 与数据库中按 UTC 保存的 ended_at 比较。
     */
    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Shanghai")
    public void cleanExpiredHistory() {
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                clock.instant().minus(Duration.ofDays(30)),
                ZoneOffset.UTC);

        int totalDeleted = 0;
        int deleted;

        do {
            deleted = deleteOneBatch(cutoff);
            totalDeleted += deleted;
        } while (deleted == BATCH_SIZE);

        if (totalDeleted > 0) {
            log.info("已清理过期 AI 建议请求 {} 条", totalDeleted);
        }
    }

    /**
     * 一批使用同一个短事务：先删建议，再删请求。
     * 任一步失败则整批回滚，下次调度可以重新处理。
     */
    private int deleteOneBatch(LocalDateTime cutoff) {
        return Objects.requireNonNull(batchTransaction.execute(ignored -> {
            List<Long> requestIds =
                    requestMapper.selectExpiredRequestIds(cutoff, BATCH_SIZE);

            if (requestIds.isEmpty()) {
                return 0;
            }

            suggestionMapper.deleteByRequestIds(requestIds);
            int requestsDeleted = requestMapper.deleteByIds(requestIds);

            if (requestsDeleted != requestIds.size()) {
                throw new IllegalStateException(
                        "AI 历史清理的请求删除数量不一致");
            }

            return requestsDeleted;
        }));
    }
}
