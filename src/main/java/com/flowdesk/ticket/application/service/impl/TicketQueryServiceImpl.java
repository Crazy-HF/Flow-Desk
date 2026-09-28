package com.flowdesk.ticket.application.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.utils.StringUtils;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.ticket.application.port.CurrentRequesterPort;
import com.flowdesk.ticket.application.query.TicketQuery;
import com.flowdesk.ticket.application.result.TicketCategorySummaryResult;
import com.flowdesk.ticket.application.result.TicketListItemResult;
import com.flowdesk.ticket.application.result.TicketUserSummaryResult;
import com.flowdesk.ticket.application.service.TicketQueryService;
import com.flowdesk.ticket.domain.TicketScope;
import com.flowdesk.ticket.infrastructure.persistence.TicketListRow;
import com.flowdesk.ticket.mapper.TicketMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class TicketQueryServiceImpl implements TicketQueryService {
    private final TicketMapper ticketMapper;
    private final CurrentRequesterPort currentRequesterPort;

    public TicketQueryServiceImpl(
            TicketMapper ticketMapper,
            CurrentRequesterPort currentRequesterPort) {
        this.ticketMapper = ticketMapper;
        this.currentRequesterPort = currentRequesterPort;
    }

    /**
     * 分页查询工单列表。
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<TicketListItemResult> page(TicketQuery query) {
        // 1. scope 必填；合法枚举由 Controller 参数绑定校验。
        if (query.getScope() == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "查询范围不能为空");
        }

        // 2. 当前用户由认证上下文确定。
        long currentUserId = currentRequesterPort.currentUserId();

        // 3. 沿用已有关键词和时间处理。
        String keywordPattern = toKeywordPattern(query.getKeyword());
        LocalDateTime createdFrom = toUtc(query.getCreatedFrom());
        LocalDateTime createdTo = toUtc(query.getCreatedTo());

        // 4. 创建分页对象。
        Page<TicketListRow> page =
                new Page<>(query.getCurrent(), query.getSize());

        // 5. Mapper 根据 scope 选择数据范围。
        Page<TicketListRow> result =
                ticketMapper.selectScopedPage(
                        page,
                        currentUserId,
                        query,
                        keywordPattern,
                        createdFrom,
                        createdTo);

        // 6. 沿用已有结果转换。
        return PageResult.from(result, this::toListItem);
    }

    /**
     * 转义 LIKE 特殊字符；SQL 使用 ! 作为转义符。
     */
    private String toKeywordPattern(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return null;
        }

        String escaped = keyword.strip()
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");

        return "%" + escaped + "%";
    }

    /**
     * 保持同一时刻，将带时区输入转换为 UTC 本地时间。
     */
    private LocalDateTime toUtc(OffsetDateTime value) {
        return value == null
                ? null
                : value.withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }

    /**
     * 数据库时间按 UTC 输出。
     */
    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    /**
     * 将数据库行转换为列表项结果。
     */
    private TicketListItemResult toListItem(TicketListRow row) {
        TicketCategorySummaryResult category =
                new TicketCategorySummaryResult(
                        row.getCategoryId(),
                        row.getCategoryName());

        TicketUserSummaryResult requester =
                new TicketUserSummaryResult(
                        row.getRequesterId(),
                        row.getRequesterDisplayName());

        TicketUserSummaryResult assignee =
                row.getAssigneeId() == null
                        ? null
                        : new TicketUserSummaryResult(
                        row.getAssigneeId(),
                        row.getAssigneeDisplayName());

        return new TicketListItemResult(
                row.getTicketNo(),
                row.getTitle(),
                category,
                row.getPriority(),
                row.getStatus(),
                requester,
                assignee,
                toOffsetDateTime(row.getActionDeadlineAt()),
                toOffsetDateTime(row.getCreatedAt()),
                toOffsetDateTime(row.getUpdatedAt()),
                row.getVersion());
    }
}
