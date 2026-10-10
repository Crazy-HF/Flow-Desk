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
import java.util.stream.Collectors;

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
    /** 待补充：只有提交人可以补充信息。 */
    private static final String STATUS_WAITING_FOR_REQUESTER = "WAITING_FOR_REQUESTER";
    /** 转交工单权限：转交动作与候选人接口共用；与处理权限分开，可以单独授予一个角色。 */
    private static final String TICKET_TRANSFER = "TICKET_TRANSFER";

    /** 关闭工单权限：与处理权限同时具备才能关闭（关闭是结束工单的处置动作）。 */
    private static final String TICKET_CLOSE = "TICKET_CLOSE";

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
     * 查询工单转交候选人。
     */
    @Override
    @Transactional(readOnly = true)
    public List<TicketAssigneeOptionResult> transferCandidates(String ticketNo) {
        //1.获取当前用户、权限
        long currentUserId = currentRequesterPort.currentUserId();

        if (!ticketReadPermissionPort.hasAuthority(TICKET_TRANSFER)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "无转交工单权限");
        }

        // 2. 与详情共用同一条可见性 SQL：无权查看与编号不存在统一 404
        TicketDetailRow visible = ticketMapper.selectVisibleDetail(
                ticketNo,
                currentUserId,
                ticketReadPermissionPort.hasAuthority(TICKET_VIEW_OWN),
                ticketReadPermissionPort.hasAuthority(TICKET_VIEW_QUEUE),
                ticketReadPermissionPort.hasAuthority(TICKET_VIEW_PARTICIPATED));

        if (visible == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }

        //3.只有「处理中」「待补充」的当前负责人能取候选人；状态或负责人不符按冲突返回。
        //可查看但不是负责人（例如历史参与者）与"动作接口的身份闸门"口径一致，都是 409。
        if (!(STATUS_PROCESSING.equals(visible.getStatus())
                || STATUS_WAITING_FOR_REQUESTER.equals(visible.getStatus()))
                || visible.getAssigneeId() == null
                || visible.getAssigneeId() != currentUserId) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "TICKET_CONFLICT",
                    "工单状态或负责人已变化，请刷新后重试",
                    visible.getVersion(),
                    visible.getStatus());
        }

        //4.通过sql筛选出 <> （不等于） currentUserId，当前工单的负责人Id的行，
        return ticketMapper.selectTransferCandidates(visible.getRequesterId(), currentUserId).stream()
                .map(row -> new TicketAssigneeOptionResult(
                        row.getId(),
                        row.getDisplayName()))
                .collect(Collectors.toList());
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
         * 处理中负责人可用的三个动作前置条件**完全相同**（处理中、本人是负责人、具备处理权限），
         * 因此共用同一条判定，而不是各写一份迟早会不一致的表达式。
         *
         * <p>{@code submit-resolution} 曾经缺失：`POST /actions/submit-resolution` 早已实现并通过
         * 真实栈验收（`docs/acceptance/2026-10-06-stage3-claim-process-resolution-confirm.json`），
         * 但详情不返回这个动作名——界面按 {@code allowedActions} 渲染按钮，于是负责人能写处理记录
         * 却交不出解决结果。阶段 3 端到端主链实测到的正是这一步。</p>
         *
         * <p>片 B 的 {@code request-supplement} 属于同一族：它在「处理中」可用，「待补充」时
         * 自己不再可用（要重新请求应先撤回，见 `docs/kickoff.md` 4.12）。</p>
         */
        boolean canSubmitResolution = canProcess;
        boolean canRequestSupplement = canProcess;

        // 待确认状态只暴露提交人的动作：转交按契约只允许「处理中」与「待补充」、关闭只允许「处理中」，
        // 在这一状态本来就不该返回，避免前端渲染按不动的按钮。
        boolean canConfirm = STATUS_WAITING_FOR_CONFIRMATION.equals(row.getStatus())
                && row.getRequesterId() == currentUserId
                && ticketReadPermissionPort.hasAuthority(TICKET_REQUESTER_ACTION);

        // 「待补充」时当前负责人可以撤回补充请求；"本人是负责人"与处理动作共用同一条判定
        boolean canWithdrawSupplementRequest =
                STATUS_WAITING_FOR_REQUESTER.equals(row.getStatus())
                        && row.getAssigneeId() != null
                        && row.getAssigneeId() == currentUserId
                        && ticketReadPermissionPort.hasAuthority(TICKET_PROCESS);

        // 提交人在「待确认」上只有两个互斥选择：确认已解决 / 反馈未解决，
        // 前置条件完全相同，直接复用 canConfirm，不复制表达式（阶段 3 的 submit-resolution 就是这样漏的）
        boolean canReportUnresolved = canConfirm;

        // 提交人在「待补充」上只有一件事可做：补充信息。人不是负责人，因此不能复用 canProcess 一族
        boolean canSupplement = STATUS_WAITING_FOR_REQUESTER.equals(row.getStatus())
                && row.getRequesterId() == currentUserId
                && ticketReadPermissionPort.hasAuthority(TICKET_REQUESTER_ACTION);

        /**
         * 「处理中」与「待补充」上，当前负责人的动作前置条件相同：状态属于这两个之一，且本人是负责人。
         */
        boolean isAssigneeOnAdjustableStatus =
                (STATUS_PROCESSING.equals(row.getStatus())
                        || STATUS_WAITING_FOR_REQUESTER.equals(row.getStatus()))
                        && row.getAssigneeId() != null
                        && row.getAssigneeId() == currentUserId;

        boolean canChangeCategory = isAssigneeOnAdjustableStatus
                && ticketReadPermissionPort.hasAuthority(TICKET_PROCESS);

        // 优先级调整与分类调整的状态、身份与权限完全相同，复用同一条判定而不是抄第二遍
        boolean canChangePriority = canChangeCategory;

        // 转交单独要求 TICKET_TRANSFER；候选人接口与这个动作共用同一条权限判定
        boolean canTransfer = isAssigneeOnAdjustableStatus
                && ticketReadPermissionPort.hasAuthority(TICKET_TRANSFER);

        // 关闭的前置条件与 canProcess 完全相同（处理中 + 本人是负责人 + TICKET_PROCESS），
        // 再叠一个 TICKET_CLOSE。两处要求不同是有意的：关闭结束工单，转交只换人
        boolean canClose = canProcess
                && ticketReadPermissionPort.hasAuthority(TICKET_CLOSE);

        // 待批准的撤销请求：只存在于「有人负责且未终结」的三个状态上，且**状态不变**，
        // 因此无法从 status 推出来——必须看请求列，界面也靠它渲染待批准提示与决策按钮
        // （2026-10-10 裁决已改口径、实现待落地：届时"有待决请求"要再分未过期 / 已过期两种，
        //   已过期时不返回批准 / 拒绝 / 撤回三格、详情显示「已过期」，见 9.3 待改清单 ①④）
        boolean hasCancelRequest = row.getCancelRequestedAt() != null;

        boolean isCancelRequestStatus =
                STATUS_PROCESSING.equals(row.getStatus())
                        || STATUS_WAITING_FOR_REQUESTER.equals(row.getStatus())
                        || STATUS_WAITING_FOR_CONFIRMATION.equals(row.getStatus());

        boolean isRequester = row.getRequesterId() != null
                && row.getRequesterId() == currentUserId;

        boolean isAssignee = row.getAssigneeId() != null
                && row.getAssigneeId() == currentUserId;

        // 提交人在两阶段里有两个互斥动作：没有待决请求时可以发起，已有待决请求时可以撤回自己那一个。
        // 权限与补充、确认、未解决反馈、直接撤销共用 TICKET_REQUESTER_ACTION
        boolean canRequestCancel = isCancelRequestStatus
                && isRequester
                && !hasCancelRequest
                && ticketReadPermissionPort.hasAuthority(TICKET_REQUESTER_ACTION);

        boolean canWithdrawCancelRequest = isCancelRequestStatus
                && isRequester
                && hasCancelRequest
                && ticketReadPermissionPort.hasAuthority(TICKET_REQUESTER_ACTION);

        // 裁决只发生在「处理中」与「待确认」：待补充期间 IT 刚把球交给员工，不允许在信息不全的时候
        // 终止整张工单（2026-10-10 用户裁决）。它仍是「可发起 / 可撤回请求」的状态（上面那条三态判定
        // 不变），只是不返回批准与拒绝；员工补充完、工单回到处理中，这两格自动回来。
        // 与 TicketServiceImpl.isCancelDecidableStatus、两条 SQL 的状态白名单必须逐字同步
        boolean isCancelDecidableStatus =
                STATUS_PROCESSING.equals(row.getStatus())
                        || STATUS_WAITING_FOR_CONFIRMATION.equals(row.getStatus());

        /**
         * 负责人在有待决请求时可以批准或拒绝：两者前置条件完全相同，共用一条判定
         * （阶段 3 的 {@code submit-resolution} 就是因为复制表达式而漏掉过一格）。
         *
         * <p>权限只要 {@code TICKET_PROCESS}，不要 {@code TICKET_CLOSE}：批准撤销让工单进入「已取消」，
         * 不是「已关闭」，与 {@code canClose} 的双权限口径是两条不同的线。</p>
         */
        boolean canDecideCancelRequest = isCancelDecidableStatus
                && isAssignee
                && hasCancelRequest
                && ticketReadPermissionPort.hasAuthority(TICKET_PROCESS);

        boolean canApproveCancel = canDecideCancelRequest;
        boolean canRejectCancel = canDecideCancelRequest;

        // 直接撤销只剩「待受理」（2026-10-08 规则变更）：那里没有负责人，没有人需要批准；
        // 其余三种非终态改走 request-cancel → approve-cancel，不再直接返回 cancel
        boolean canCancel = STATUS_PENDING.equals(row.getStatus())
                && isRequester
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

        if (canRequestSupplement) {
            allowedActions.add("request-supplement");
        }

        if (canConfirm) {
            allowedActions.add("confirm-resolution");
        }

        if (canWithdrawSupplementRequest) {
            allowedActions.add("withdraw-supplement-request");
        }

        if (canReportUnresolved) {
            allowedActions.add("report-unresolved");
        }

        if (canSupplement) {
            allowedActions.add("supplement");
        }

        if (canChangeCategory) {
            allowedActions.add("change-category");
        }

        if (canChangePriority) {
            allowedActions.add("change-priority");
        }

        if (canTransfer) {
            allowedActions.add("transfer");
        }

        if (canClose) {
            allowedActions.add("close");
        }

        if (canRequestCancel) {
            allowedActions.add("request-cancel");
        }

        if (canWithdrawCancelRequest) {
            allowedActions.add("withdraw-cancel-request");
        }

        if (canApproveCancel) {
            allowedActions.add("approve-cancel");
        }

        if (canRejectCancel) {
            allowedActions.add("reject-cancel");
        }

        if (canCancel) {
            allowedActions.add("cancel");
        }

        // 待批准的撤销请求：状态不变，所以详情必须单独返回它，否则界面渲染不出待批准提示
        TicketCancelRequestResult cancelRequest = hasCancelRequest
                ? new TicketCancelRequestResult(
                        toOffsetDateTime(row.getCancelRequestedAt()),
                        toOffsetDateTime(row.getCancelRequestDeadlineAt()),
                        row.getCancelRequestReason())
                : null;

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
                cancelRequest,
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
            // 两阶段撤销：发起时状态不变但带响应期限；批准、拒绝、撤回三者状态与期限的变化
            // 分别体现在 toStatus 与是否有 reason 上，因此与"撤回补充请求"一族共用同一个上下文形状
            case "CANCELLATION_REQUEST" -> {
                putContext(context, "reason", row.getReason());
                putContext(
                        context, "deadlineAt",
                        toOffsetDateTime(row.getDeadlineAt()));
                putStatusContext(context, row);
            }
            case "CANCELLATION_APPROVED",
                 "CANCELLATION_REJECTED",
                 "CANCELLATION_REQUEST_WITHDRAWN" -> {
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
