package com.flowdesk.ticket.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.auth.domain.AuthSession;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.utils.StringUtils;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.ticket.application.port.CurrentRequesterPort;
import com.flowdesk.ticket.application.port.TicketClaimantPort;
import com.flowdesk.ticket.application.query.TicketQuery;
import com.flowdesk.ticket.application.query.TicketRecordQuery;
import com.flowdesk.ticket.application.result.*;
import com.flowdesk.ticket.application.service.TicketQueryService;
import com.flowdesk.ticket.domain.Ticket;
import com.flowdesk.ticket.infrastructure.persistence.TicketListRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketRecordRow;
import com.flowdesk.ticket.mapper.TicketMapper;
import com.flowdesk.ticket.mapper.TicketRecordMapper;
import org.springframework.http.HttpStatus;
import com.flowdesk.ticket.application.port.TicketReadPermissionPort;
import com.flowdesk.ticket.infrastructure.persistence.TicketDetailRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TicketQueryServiceImpl implements TicketQueryService {
    //查看自己的工单
    private static final String TICKET_VIEW_OWN = "TICKET_VIEW_OWN";
    //查看待受理队列
    private static final String TICKET_VIEW_QUEUE = "TICKET_VIEW_QUEUE";
    //查看参与工单
    private static final String TICKET_VIEW_PARTICIPATED = "TICKET_VIEW_PARTICIPATED";
    private static final String TICKET_CLAIM = "TICKET_CLAIM";
    /** 处理工单权限：详情动作提示与动作接口使用同一编码。 */
    private static final String TICKET_PROCESS = "TICKET_PROCESS";
    /** 提交人动作权限：补充、确认、未解决反馈与撤销。 */
    private static final String TICKET_REQUESTER_ACTION = "TICKET_REQUESTER_ACTION";
    /** 待处理：无人负责的公共队列状态。 */
    private static final String STATUS_PENDING = "PENDING";
    /** 处理中：只有当前负责人可以追加处理记录。 */
    private static final String STATUS_PROCESSING = "PROCESSING";
    /** 待确认：只有提交人可以确认问题已解决。 */
    private static final String STATUS_WAITING_FOR_CONFIRMATION = "WAITING_FOR_CONFIRMATION";

    private final TicketMapper ticketMapper;
    private final CurrentRequesterPort currentRequesterPort;
    private final TicketReadPermissionPort ticketReadPermissionPort;
    private final TicketRecordMapper ticketRecordMapper;
    private final TicketClaimantPort ticketClaimantPort;

    public TicketQueryServiceImpl(
            TicketMapper ticketMapper,
            CurrentRequesterPort currentRequesterPort,
            TicketReadPermissionPort ticketReadPermissionPort,
            TicketRecordMapper ticketRecordMapper,
            TicketClaimantPort ticketClaimantPort) {
        this.ticketMapper = ticketMapper;
        this.currentRequesterPort = currentRequesterPort;
        this.ticketReadPermissionPort = ticketReadPermissionPort;
        this.ticketRecordMapper = ticketRecordMapper;
        this.ticketClaimantPort = ticketClaimantPort;
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
     * 查询工单详情。
     */
    @Override
    @Transactional(readOnly = true)
    public TicketDetailResult detail(String ticketNo) {
        // 1. 当前用户来自认证上下文。
        long currentUserId = currentRequesterPort.currentUserId();

        // 2. 分别读取三项查询权限。
        boolean canViewOwn =
                ticketReadPermissionPort.hasAuthority(TICKET_VIEW_OWN);
        boolean canViewQueue =
                ticketReadPermissionPort.hasAuthority(TICKET_VIEW_QUEUE);
        boolean canViewParticipated =
                ticketReadPermissionPort.hasAuthority(TICKET_VIEW_PARTICIPATED);

        // 3. 权限和工单关系必须同时成立。
        TicketDetailRow row = ticketMapper.selectVisibleDetail(
                ticketNo,
                currentUserId,
                canViewOwn,
                canViewQueue,
                canViewParticipated);

        // 4. 无权与不存在统一返回 404。
        if (row == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }

        return toDetail(row, currentUserId);
    }

    /**
     * 分页查询工单记录。
     */
    @Override
    @Transactional(readOnly = true)
    public PageResult<TicketRecordResult> records(
            String ticketNo, TicketRecordQuery query) {
        // 1. 获取当前身份和权限。
        long currentUserId = currentRequesterPort.currentUserId();

        boolean canViewOwn =
                ticketReadPermissionPort.hasAuthority(TICKET_VIEW_OWN);
        boolean canViewQueue =
                ticketReadPermissionPort.hasAuthority(TICKET_VIEW_QUEUE);
        boolean canViewParticipated =
                ticketReadPermissionPort.hasAuthority(TICKET_VIEW_PARTICIPATED);

        // 2. 与详情使用相同的可见性 SQL。
        TicketDetailRow ticket = ticketMapper.selectVisibleDetail(
                ticketNo,
                currentUserId,
                canViewOwn,
                canViewQueue,
                canViewParticipated);

        if (ticket == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }

        // 3. 只查询这张已授权工单的记录。
        Page<TicketRecordRow> page =
                new Page<>(query.getCurrent(), query.getSize());

        Page<TicketRecordRow> result =
                ticketRecordMapper.selectTimelinePage(page, ticket.getId());

        // 4. 返回分页信封，不返回数据库实体。
        return PageResult.from(result, this::toRecordResult);
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
     * 列表项结果。
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

    /**
     * 详情结果。
     */
    private TicketDetailResult toDetail(TicketDetailRow row, long currentUserId) {
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

        boolean canClaim = STATUS_PENDING.equals(row.getStatus())
                && row.getAssigneeId() == null
                && row.getRequesterId() != currentUserId
                && ticketReadPermissionPort.hasAuthority(TICKET_VIEW_QUEUE)
                && ticketReadPermissionPort.hasAuthority(TICKET_CLAIM)
                && ticketClaimantPort.isEligibleClaimant(currentUserId);

        boolean canProcess = STATUS_PROCESSING.equals(row.getStatus())
                && row.getAssigneeId() != null
                && row.getAssigneeId() == currentUserId
                && ticketReadPermissionPort.hasAuthority(TICKET_PROCESS);

        /**
         * 提交解决结果与追加处理记录的前置条件**完全相同**（处理中、本人是负责人、具备处理权限），
         * 所以直接复用同一条判定，而不是复制一份迟早会不一致的表达式。
         *
         * <p>这个分支曾经缺失：`POST /actions/submit-resolution` 早已实现并通过真实栈验收
         * （`docs/acceptance/2026-10-06-stage3-claim-process-resolution-confirm.json`），
         * 但详情不返回这个动作名——界面按 `allowedActions` 渲染按钮，于是负责人能写处理记录
         * 却交不出解决结果。阶段 3 端到端主链实测到的正是这一步。</p>
         */
        boolean canSubmitResolution = canProcess;

        // 待确认状态下 IT 侧只暴露已实现的动作：报告未解决、转交与调整尚未实现，
        // 因此这里不返回，避免前端渲染按不动的按钮。
        boolean canConfirm = STATUS_WAITING_FOR_CONFIRMATION.equals(row.getStatus())
                && row.getRequesterId() == currentUserId
                && ticketReadPermissionPort.hasAuthority(TICKET_REQUESTER_ACTION);

        // 一个动作一个条件，动作名与接口路径末段逐字一致。
        List<String> allowedActions = new ArrayList<>();

        if (canClaim) {
            allowedActions.add("claim");
        }

        if (canProcess) {
            allowedActions.add("add-processing-record");
        }

        if (canSubmitResolution) {
            allowedActions.add("submit-resolution");
        }

        if (canConfirm) {
            allowedActions.add("confirm-resolution");
        }

        return new TicketDetailResult(
                row.getTicketNo(),
                row.getTitle(),
                row.getDescription(),
                category,
                row.getPriority(),
                row.getStatus(),
                requester,
                assignee,
                toOffsetDateTime(row.getActionDeadlineAt()),
                row.getVersion(),
                row.getCompletionMethod(),
                row.getCloseMethod(),
                row.getCloseReason(),
                toOffsetDateTime(row.getEndedAt()),
                toOffsetDateTime(row.getCreatedAt()),
                toOffsetDateTime(row.getUpdatedAt()),
                allowedActions);
    }

    /**
     * 记录结果。
     */
    private TicketRecordResult toRecordResult(TicketRecordRow row) {
        TicketUserSummaryResult actor =
                row.getActorUserId() == null
                        ? null
                        : new TicketUserSummaryResult(
                        row.getActorUserId(),
                        row.getActorDisplayName());

        return new TicketRecordResult(
                row.getSequenceNo(),
                row.getRecordType(),
                row.getActorType(),
                actor,
                toOffsetDateTime(row.getCreatedAt()),
                toRecordContext(row));
    }

    /**
     * 记录上下文。
     */
    private Map<String, Object> toRecordContext(TicketRecordRow row) {
        Map<String, Object> context = new LinkedHashMap<>();

        switch (row.getRecordType()) {
            case "CREATE" -> {
                putContext(context, "toStatus", row.getToStatus());
                putContext(context, "categoryId", row.getToCategoryId());
                putContext(context, "priority", row.getToPriority());
            }
            case "CLAIM" -> {
                putContext(context, "assigneeId", row.getToAssigneeId());
                putStatusContext(context, row);
            }
            case "PROCESS", "REQUESTER_SUPPLEMENT" ->
                    putContext(context, "content", row.getContent());

            case "CATEGORY_CHANGE" -> {
                putContext(context, "fromCategoryId", row.getFromCategoryId());
                putContext(context, "toCategoryId", row.getToCategoryId());
                putContext(context, "reason", row.getReason());
            }
            case "PRIORITY_CHANGE" -> {
                putContext(context, "fromPriority", row.getFromPriority());
                putContext(context, "toPriority", row.getToPriority());
                putContext(context, "reason", row.getReason());
            }
            case "TRANSFER", "ADMIN_HANDOFF" -> {
                putContext(context, "fromAssigneeId", row.getFromAssigneeId());
                putContext(context, "toAssigneeId", row.getToAssigneeId());
                putContext(context, "reason", row.getReason());
            }
            case "SUPPLEMENT_REQUEST", "RESOLUTION" -> {
                putContext(context, "content", row.getContent());
                putContext(
                        context, "deadlineAt",
                        toOffsetDateTime(row.getDeadlineAt()));
                putStatusContext(context, row);
            }
            case "SUPPLEMENT_REQUEST_WITHDRAWN",
                 "UNSATISFIED_FEEDBACK",
                 "CANCELLATION" -> {
                putContext(context, "reason", row.getReason());
                putStatusContext(context, row);
            }
            case "COMPLETION" -> {
                putContext(context, "completionMethod", row.getCompletionMethod());
                putStatusContext(context, row);
            }
            case "CLOSURE" -> {
                putContext(context, "closeMethod", row.getCloseMethod());
                putContext(context, "closeReason", row.getCloseReason());
                putContext(context, "reason", row.getReason());
                putStatusContext(context, row);
            }
            default -> throw new IllegalStateException(
                    "不支持的工单记录类型：" + row.getRecordType());
        }

        return context;
    }

    /**
     * 记录状态上下文。
     */
    private void putStatusContext(
            Map<String, Object> context, TicketRecordRow row) {
        putContext(context, "fromStatus", row.getFromStatus());
        putContext(context, "toStatus", row.getToStatus());
    }

    /**
     * 放置上下文。
     */
    private void putContext(
            Map<String, Object> context, String name, Object value) {
        if (value != null) {
            context.put(name, value);
        }
    }
}
