package com.flowdesk.ticket.application.service.impl;

import com.flowdesk.common.exception.ApiException;
import com.flowdesk.ticket.application.command.*;
import com.flowdesk.ticket.application.port.CategoryAvailabilityPort;
import com.flowdesk.ticket.application.port.CurrentRequesterPort;
import com.flowdesk.ticket.application.port.TicketClaimantPort;
import com.flowdesk.ticket.application.port.TicketReadPermissionPort;
import com.flowdesk.ticket.config.TicketProperties;
import com.flowdesk.ticket.application.result.TicketActionResult;
import com.flowdesk.ticket.application.result.TicketCreatedResult;
import com.flowdesk.ticket.application.result.TicketUserSummaryResult;
import com.flowdesk.ticket.application.service.TicketService;
import com.flowdesk.ticket.domain.Ticket;
import com.flowdesk.ticket.domain.TicketRecord;
import com.flowdesk.ticket.infrastructure.persistence.TicketDetailRow;
import com.flowdesk.ticket.mapper.TicketMapper;
import com.flowdesk.ticket.mapper.TicketDailySequenceMapper;
import com.flowdesk.ticket.mapper.TicketParticipantMapper;
import com.flowdesk.ticket.mapper.TicketRecordMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

@Service
public class TicketServiceImpl implements TicketService {

    /** 待处理状态 */
    private static final String PENDING = "PENDING";
    /** 领取工单权限由动态 RBAC 授予，不能从登录身份推定。 */
    private static final String TICKET_CLAIM = "TICKET_CLAIM";

    /** 处理中状态：追加处理记录只允许发生在这个状态。 */
    private static final String PROCESSING = "PROCESSING";
    /** 处理工单权限由动态 RBAC 授予，不能从"当前负责人"推定。 */
    private static final String TICKET_PROCESS = "TICKET_PROCESS";

    /** 待确认状态：只有提交人可以确认解决结果。 */
    private static final String WAITING_FOR_CONFIRMATION = "WAITING_FOR_CONFIRMATION";
    /** 提交人动作权限：补充、确认、未解决反馈与撤销。 */
    private static final String TICKET_REQUESTER_ACTION = "TICKET_REQUESTER_ACTION";

    /** 处理正文去除首尾空白后的长度上限，与请求校验保持一致。 */
    private static final int MAX_CONTENT_LENGTH = 10000;

    /** 待补充：只有当前负责人可以撤回补充请求。 */
    private static final String WAITING_FOR_REQUESTER = "WAITING_FOR_REQUESTER";

    /** 原因类动作（撤回、未解决、转交、调整、取消、关闭）的长度上限，与 @Size 一致。 */
    private static final int MAX_REASON_LENGTH = 1000;

    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("Asia/Shanghai");

    private final Clock clock;
    private final TicketMapper ticketMapper;
    private final TicketDailySequenceMapper ticketDailySequenceMapper;
    private final TicketRecordMapper ticketRecordMapper;
    private final CurrentRequesterPort currentRequesterPort;
    private final CategoryAvailabilityPort categoryAvailabilityPort;
    private final TransactionTemplate transactionTemplate;
    private final TicketReadPermissionPort ticketReadPermissionPort;
    private final TicketClaimantPort ticketClaimPort;
    private final TicketParticipantMapper ticketParticipantMapper;
    /** 确认期限来自配置，服务端计算，不接受客户端传入。 */
    private final TicketProperties ticketProperties;


    public TicketServiceImpl(
            Clock clock,
            TicketMapper ticketMapper,
            TicketDailySequenceMapper ticketDailySequenceMapper,
            TicketRecordMapper ticketRecordMapper,
            CurrentRequesterPort currentRequesterPort,
            CategoryAvailabilityPort categoryAvailabilityPort,
            TicketReadPermissionPort ticketReadPermissionPort,
            PlatformTransactionManager transactionManager,
            TicketClaimantPort ticketClaimPort,
            TicketParticipantMapper ticketParticipantMapper,
            TicketProperties ticketProperties) {
        this.clock = clock;
        this.ticketMapper = ticketMapper;
        this.ticketDailySequenceMapper = ticketDailySequenceMapper;
        this.ticketRecordMapper = ticketRecordMapper;
        this.currentRequesterPort = currentRequesterPort;
        this.categoryAvailabilityPort = categoryAvailabilityPort;
        this.ticketReadPermissionPort = ticketReadPermissionPort;
        this.ticketClaimPort = ticketClaimPort;
        this.ticketParticipantMapper = ticketParticipantMapper;
        this.ticketProperties = ticketProperties;

        this.transactionTemplate =
                new TransactionTemplate(transactionManager);

        // 每次执行使用独立事务，确保冲突后的查询使用新快照。
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 创建工单
     * 同一用户、同一提交键返回首次创建结果。
     */
    @Override
    public TicketCreatedResult create(CreateTicketCommand command) {
        //1.获取当前提交人
        Long requesterId = currentRequesterPort.currentUserId();

        //2.处理用户submissionKey：HTTP 入口已由 Bean Validation 校验 UUID，这里兜底非 HTTP 调用方
        String rawSubmissionKey = command.submissionKey();
        if (rawSubmissionKey == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "提交键必须是 UUID");
        }
        String submissionKey;
        try {
            submissionKey = UUID.fromString(rawSubmissionKey).toString();
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "提交键必须是 UUID");
        }

        try {
            return Objects.requireNonNull(
                    transactionTemplate.execute(transactionStatus -> {
                        //3.根据唯一键处理已成功创建过的请求
                        Ticket existing = ticketMapper.selectCreationByRequesterAndSubmissionKey(requesterId, submissionKey);

                        //4.存在则继续提交
                        if (existing != null)
                            return toCreatedResult(existing);

                        //5.首次提交才执行分类校验、创建
                        return createTicket(command, requesterId, submissionKey);
                    })
            );
        } catch (DuplicateKeyException exception) {
            //execute 抛出异常时创建事务已经回滚；用新事务查并发请求提交的原工单
            Ticket existing = transactionTemplate.execute(transactionStatus ->
                    ticketMapper.selectCreationByRequesterAndSubmissionKey(requesterId, submissionKey));

            //提交键命中：并发重试的同一请求，返回首次创建结果
            if (existing != null) {
                return toCreatedResult(existing);
            }

            //提交键未命中：冲突来自其他唯一键（如工单编号），不能当作创建成功，也不回落 500
            ApiException conflict = new ApiException(
                    HttpStatus.CONFLICT,
                    "TICKET_CREATE_CONFLICT",
                    "工单创建冲突，请重试");
            conflict.initCause(exception);
            throw conflict;
        }
    }

    /** 领取工单：权限、身份、资源关系与并发条件都在服务端复核。 */
    @Override
    @Transactional
    public TicketActionResult claim(String ticketNo, ClaimTicketCommand command) {
        //1.校验身份与工单
        long actorId = currentRequesterPort.currentUserId();
        //校验权限
        boolean claimPermission = ticketReadPermissionPort.hasAuthority(TICKET_CLAIM);

        if (!claimPermission) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "无领取工单权限");
        }

        // 会话保证请求已认证；落库前仍校验当前账号和 IT 角色，避免授权变化竞态。
        TicketUserSummaryResult claimant = ticketClaimPort.lockEligibleClaimant(actorId);

        if (claimant == null) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "当前用户不能领取工单");
        }

        // 复用详情的权限与资源关系判断；无权查看和编号不存在统一为 404。
        TicketDetailRow visible = ticketMapper.selectVisibleDetail(
                ticketNo,
                actorId,
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_OWN"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_QUEUE"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_PARTICIPATED"));

        if (visible == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }

        if (actorId == visible.getRequesterId()) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "不能领取自己提交的工单");
        }

        if (!PENDING.equals(visible.getStatus())
                || visible.getAssigneeId() != null
                || !Objects.equals(command.version(), visible.getVersion())) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "TICKET_CONFLICT",
                    "工单状态或版本已变化，请刷新后重试",
                    visible.getVersion(), visible.getStatus());
        }


        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        //2.领取工单，更新ticket
        // 最终并发判定在数据库条件更新中完成；两名 IT 同时领取只能有一人更新成功。
        int updatedRows = ticketMapper.claimPending(
                visible.getId(),
                command.version(),
                actorId,
                now);
        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());
            if (current == null) {
                throw new IllegalStateException("领取冲突后无法读取工单快照");
            }
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "TICKET_CONFLICT",
                    "工单状态或版本已变化，请刷新后重试",
                    current.getVersion(), current.getStatus());
        }

        // 本事务持有工单更新锁，读回本次递增后的记录序号。
        Ticket updated = ticketMapper.selectById(visible.getId());
        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("领取后无法读取工单快照");
        }

        // 记录曾经负责过工单的 IT 用户，供参与历史与后续可见性查询。
        ticketParticipantMapper.recordAssignment(
                updated.getId(),
                actorId,
                now);

        // 追加不可变的业务时间线，描述本次领取动作。
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("CLAIM");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setFromStatus(PENDING);
        record.setToStatus("PROCESSING");
        record.setToAssigneeId(actorId);
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("领取记录插入失败");
        }

        return new TicketActionResult(
                updated.getTicketNo(),
                "PROCESSING",
                claimant,
                null,
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }

    /** 当前负责人追加处理记录。 */
    @Override
    @Transactional
    public TicketActionResult addProcessingRecord(String ticketNo, AddProcessingRecordCommand command) {
        //1.获取当前处理人身份、权限
        long actorId = currentRequesterPort.currentUserId();
        if(!ticketReadPermissionPort.hasAuthority(TICKET_PROCESS)){
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "无处理工单权限");
        }

        // 2. 可见性：无权查看与编号不存在统一 404，不向调用方确认工单是否存在
        TicketDetailRow visible = ticketMapper.selectVisibleDetail(
                ticketNo,
                actorId,
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_OWN"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_QUEUE"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_PARTICIPATED"));

        if (visible == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }

        // 3. 只有当前负责人能在 PROCESSING 上追加记录
        String content = command.content();
        if (!PROCESSING.equals(visible.getStatus())
                || visible.getAssigneeId() == null
                || visible.getAssigneeId() != actorId) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 4. 长度上限与请求校验保持一致；能过 Controller 校验后这里只兜底超长内容
        if (content == null || content.isEmpty() || content.length() > MAX_CONTENT_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "处理内容长度必须在 1 到 10000 之间");
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 5. 条件更新是唯一胜者判定：版本、状态与负责人都在 WHERE 里
        int updatedRows = ticketMapper.advanceAssigneeAction(
                visible.getId(),
                command.version(),
                actorId,
                now);

        //根据Id锁定读当前处理工单
        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());

            if (current == null) {
                throw new IllegalStateException("处理记录冲突后无法读取工单快照");
            }

            throw conflict(current.getVersion(), current.getStatus());
        }

        // 6. 本事务已持有该行更新锁，读回递增后的记录序号
        Ticket updated = ticketMapper.selectById(visible.getId());

        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("追加处理记录后无法读取工单快照");
        }

        // 7. 追加不可变时间线；本动作不改变状态，因此两侧状态都写 PROCESSING
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("PROCESS");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setContent(content);
        record.setFromStatus(PROCESSING);
        record.setToStatus(PROCESSING);
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("处理记录插入失败");
        }

        // 8. 负责人与状态都没变，摘要直接取本事务读到的可见快照
        TicketUserSummaryResult assignee = new TicketUserSummaryResult(
                visible.getAssigneeId(),
                visible.getAssigneeDisplayName());

        return new TicketActionResult(
                updated.getTicketNo(),
                PROCESSING,
                assignee,
                null,
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }

    /** 提交处理结果。 */
    @Override
    @Transactional
    public TicketActionResult submitResolution(String ticketNo, SubmitResolutionCommand command) {
        //1. 获取当前处理人身份、权限
        long actorId = currentRequesterPort.currentUserId();
        if(!ticketReadPermissionPort.hasAuthority(TICKET_PROCESS)){
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "无处理工单权限");
        }

        // 2. 可见性：无权查看与编号不存在统一 404
        TicketDetailRow visible = ticketMapper.selectVisibleDetail(
                ticketNo,
                actorId,
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_OWN"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_QUEUE"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_PARTICIPATED"));

        if (visible == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }

        // 3. 只有当前负责人能在 PROCESSING 上提交解决结果
        String content = command.content();

        if (!PROCESSING.equals(visible.getStatus())
                || visible.getAssigneeId() == null
                || visible.getAssigneeId() != actorId) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        if (content == null || content.isEmpty() || content.length() > MAX_CONTENT_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "解决结果长度必须在 1 到 10000 之间");
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 4. 确认期限从提交时刻起算，长度来自配置
        LocalDateTime deadlineAt = now.plus(ticketProperties.confirmationWindow());

        // 5. 条件更新是唯一胜者判定
        int updatedRows = ticketMapper.submitResolution(
                visible.getId(),
                command.version(),
                actorId,
                deadlineAt,
                now);

        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());

            if (current == null) {
                throw new IllegalStateException("提交解决结果冲突后无法读取工单快照");
            }

            throw conflict(current.getVersion(), current.getStatus());
        }

        Ticket updated = ticketMapper.selectById(visible.getId());

        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("提交解决结果后无法读取工单快照");
        }

        // 6. 追加不可变时间线：正文与本次确认期限
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("RESOLUTION");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setContent(content);
        record.setFromStatus(PROCESSING);
        record.setToStatus(WAITING_FOR_CONFIRMATION);
        record.setDeadlineAt(deadlineAt);
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("解决结果记录插入失败");
        }

        TicketUserSummaryResult assignee = new TicketUserSummaryResult(
                visible.getAssigneeId(),
                visible.getAssigneeDisplayName());

        return new TicketActionResult(
                updated.getTicketNo(),
                WAITING_FOR_CONFIRMATION,
                assignee,
                deadlineAt.atOffset(ZoneOffset.UTC),
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }

    /** 确认处理结果。 */
    @Override
    @Transactional
    public TicketActionResult confirmResolution(String ticketNo, ConfirmResolutionCommand command) {
        // 1. 身份与提交人动作权限
        long actorId = currentRequesterPort.currentUserId();

        if (!ticketReadPermissionPort.hasAuthority(TICKET_REQUESTER_ACTION)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "无提交人操作权限");
        }

        TicketDetailRow visible = ticketMapper.selectVisibleDetail(
                ticketNo,
                actorId,
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_OWN"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_QUEUE"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_PARTICIPATED"));

        if (visible == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }

        // 2. 只有提交人能在待确认上确认；非提交人能看到工单但不是提交人，按冲突返回
        if (!WAITING_FOR_CONFIRMATION.equals(visible.getStatus())
                || visible.getRequesterId() == null
                || visible.getRequesterId() != actorId) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 3. 条件更新同时清空期限、写入完成方式与结束时间
        int updatedRows = ticketMapper.confirmResolution(
                visible.getId(),
                command.version(),
                actorId,
                now);

        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());

            if (current == null) {
                throw new IllegalStateException("确认解决结果冲突后无法读取工单快照");
            }

            throw conflict(current.getVersion(), current.getStatus());
        }

        Ticket updated = ticketMapper.selectById(visible.getId());

        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("确认解决结果后无法读取工单快照");
        }

        // 4. 完成记录只表达完成方式与状态迁移
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("COMPLETION");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setCompletionMethod("REQUESTER_CONFIRMED");
        record.setFromStatus(WAITING_FOR_CONFIRMATION);
        record.setToStatus("COMPLETED");
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("完成记录插入失败");
        }

        // 5. 终态期限已失效；负责人按快照保留，仍取详情可见行的摘要
        TicketUserSummaryResult assignee = visible.getAssigneeId() == null
                ? null
                : new TicketUserSummaryResult(
                visible.getAssigneeId(),
                visible.getAssigneeDisplayName());

        return new TicketActionResult(
                updated.getTicketNo(),
                "COMPLETED",
                assignee,
                null,
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }

    /** 当前负责人撤回补充请求：回到「处理中」，原补充期限失效。 */
    @Override
    @Transactional
    public TicketActionResult withdrawSupplementRequest(String ticketNo, WithdrawSupplementRequestCommand command) {
        //1.身份与提交人动作权限
        long actorId = currentRequesterPort.currentUserId();

        if (!ticketReadPermissionPort.hasAuthority(TICKET_PROCESS)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "无处理工单权限");
        }

        // 2. 可见性：无权查看与编号不存在统一 404，不向调用方确认工单是否存在
        TicketDetailRow visible = ticketMapper.selectVisibleDetail(
                ticketNo,
                actorId,
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_OWN"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_QUEUE"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_PARTICIPATED"));

        if (visible == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }

        // 3. 只有「待补充」的当前负责人可以撤回
        if (!WAITING_FOR_REQUESTER.equals(visible.getStatus())
                || visible.getAssigneeId() == null
                || visible.getAssigneeId() != actorId) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 4. 版本必须与客户端读到的一致
        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 5. 原因长度与校验注解一致；能过 Controller 后这里只兜底
        //    null 必须在 equals/isEmpty 之前判掉：非 HTTP 调用方（别的服务、脚本、测试）不走 Bean Validation
        String reason = command.reason();
        if (reason == null || reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "撤回原因长度必须在 1 到 1000 之间");
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 6. 条件更新是唯一胜者判定：状态、负责人与版本都在 WHERE 里
        int updatedRows = ticketMapper.withdrawSupplementRequest(
                visible.getId(),
                command.version(),
                actorId,
                now);

        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());
            if (current == null) {
                throw new IllegalStateException("撤回补充请求冲突后无法读取工单快照");
            }
            throw conflict(current.getVersion(), current.getStatus());
        }

        Ticket updated = ticketMapper.selectById(visible.getId());
        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("撤回补充请求后无法读取工单快照");
        }

        // 7. 不可变时间线：撤回原因 + 状态迁移；期限由条件更新一并清空
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("SUPPLEMENT_REQUEST_WITHDRAWN");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setReason(reason);
        record.setFromStatus(WAITING_FOR_REQUESTER);
        record.setToStatus(PROCESSING);
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("撤回补充请求记录插入失败");
        }

        // 8. 负责人没变，摘要直接取本事务读到的可见快照
        TicketUserSummaryResult assignee = new TicketUserSummaryResult(
                visible.getAssigneeId(),
                visible.getAssigneeDisplayName());

        return new TicketActionResult(
                updated.getTicketNo(),
                PROCESSING,
                assignee,
                null,
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }


    /** 提交人反馈问题未解决：回到「处理中」，原确认期限失效，负责人保留。 */
    @Override
    @Transactional
    public TicketActionResult reportUnresolved(
            String ticketNo, ReportUnresolvedCommand command) {
        // 1. 身份与提交人动作权限
        long actorId = currentRequesterPort.currentUserId();
        if (!ticketReadPermissionPort.hasAuthority(TICKET_REQUESTER_ACTION)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "无提交人操作权限");
        }

        TicketDetailRow visible = ticketMapper.selectVisibleDetail(
                ticketNo,
                actorId,
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_OWN"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_QUEUE"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_PARTICIPATED"));

        if (visible == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }

        // 2. 只有提交人能在「待确认」上反馈未解决；负责人能看到工单但不是提交人，按冲突返回
        if (!WAITING_FOR_CONFIRMATION.equals(visible.getStatus())
                || visible.getRequesterId() == null
                || visible.getRequesterId() != actorId) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        String reason = command.reason();
        if (reason == null || reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "未解决原因长度必须在 1 到 1000 之间");
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 3. 条件更新：同时清空确认期限；assignee_id 不动（退回不等于换人）
        int updatedRows = ticketMapper.reportUnresolved(
                visible.getId(),
                command.version(),
                actorId,
                now);

        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());
            if (current == null) {
                throw new IllegalStateException("反馈未解决冲突后无法读取工单快照");
            }
            throw conflict(current.getVersion(), current.getStatus());
        }

        Ticket updated = ticketMapper.selectById(visible.getId());
        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("反馈未解决后无法读取工单快照");
        }

        // 4. 未解决反馈是一次业务事件，不是"驳回"状态：状态回到处理中，解决结果留在时间线上
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("UNSATISFIED_FEEDBACK");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setReason(reason);
        record.setFromStatus(WAITING_FOR_CONFIRMATION);
        record.setToStatus(PROCESSING);
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("未解决反馈记录插入失败");
        }

        TicketUserSummaryResult assignee = visible.getAssigneeId() == null
                ? null
                : new TicketUserSummaryResult(
                visible.getAssigneeId(),
                visible.getAssigneeDisplayName());

        return new TicketActionResult(
                updated.getTicketNo(),
                PROCESSING,
                assignee,
                null,
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }

    /** 当前负责人请求员工补充信息：进入「待补充」，期限由服务端按配置计算。 */
    @Override
    @Transactional
    public TicketActionResult requestSupplement(
            String ticketNo, RequestSupplementCommand command) {
        // 1. 身份与处理权限
        long actorId = currentRequesterPort.currentUserId();
        if (!ticketReadPermissionPort.hasAuthority(TICKET_PROCESS)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "无处理工单权限");
        }

        // 2. 可见性：无权查看与编号不存在统一 404，不向调用方确认工单是否存在
        TicketDetailRow visible = ticketMapper.selectVisibleDetail(
                ticketNo,
                actorId,
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_OWN"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_QUEUE"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_PARTICIPATED"));

        if (visible == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }

        // 3. 只有「处理中」的当前负责人可以请求补充；「待补充」期间要再次请求应先撤回
        if (!PROCESSING.equals(visible.getStatus())
                || visible.getAssigneeId() == null
                || visible.getAssigneeId() != actorId) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 4. 版本必须与客户端读到的一致
        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 5. 必须写清需要补充什么；null 必须在 isEmpty 之前判掉（非 HTTP 调用方不走 Bean Validation）
        String content = command.content();
        if (content == null || content.isEmpty() || content.length() > MAX_CONTENT_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "需要补充的内容长度必须在 1 到 10000 之间");
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 6. 补充期限从本次请求时刻起算
        LocalDateTime deadlineAt = now.plus(ticketProperties.supplementWindow());

        // 7. 条件更新是唯一胜者判定：状态、负责人与版本都在 WHERE 里；
        //    状态与期限必须在同一条 UPDATE 内落库，否则中间态会撞 ck_ticket_status_deadline
        int updatedRows = ticketMapper.requestSupplement(
                visible.getId(),
                command.version(),
                actorId,
                deadlineAt,
                now);

        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());
            if (current == null) {
                throw new IllegalStateException("请求补充冲突后无法读取工单快照");
            }
            throw conflict(current.getVersion(), current.getStatus());
        }

        Ticket updated = ticketMapper.selectById(visible.getId());
        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("请求补充后无法读取工单快照");
        }

        // 8. 不可变时间线：需要补充的内容与本次期限；负责人没变，仍由本人继续处理
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("SUPPLEMENT_REQUEST");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setContent(content);
        record.setFromStatus(PROCESSING);
        record.setToStatus(WAITING_FOR_REQUESTER);
        record.setDeadlineAt(deadlineAt);
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("补充请求记录插入失败");
        }

        TicketUserSummaryResult assignee = new TicketUserSummaryResult(
                visible.getAssigneeId(),
                visible.getAssigneeDisplayName());

        return new TicketActionResult(
                updated.getTicketNo(),
                WAITING_FOR_REQUESTER,
                assignee,
                now.plus(ticketProperties.supplementWindow()).atOffset(ZoneOffset.UTC),
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }

    /** 提交人补充信息：回到「处理中」，原补充期限失效，原负责人继续处理。 */
    @Override
    @Transactional
    public TicketActionResult supplement(String ticketNo, SupplementCommand command) {
        // 1. 身份与提交人动作权限
        long actorId = currentRequesterPort.currentUserId();
        if (!ticketReadPermissionPort.hasAuthority(TICKET_REQUESTER_ACTION)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "无提交人操作权限");
        }

        // 2. 可见性：无权查看与编号不存在统一 404
        TicketDetailRow visible = ticketMapper.selectVisibleDetail(
                ticketNo,
                actorId,
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_OWN"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_QUEUE"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_PARTICIPATED"));

        if (visible == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }

        // 3. 只有提交人能在「待补充」上补充信息；负责人能看到工单但不是提交人，按冲突返回
        if (!WAITING_FOR_REQUESTER.equals(visible.getStatus())
                || visible.getRequesterId() == null
                || visible.getRequesterId() != actorId) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 4. 版本必须与客户端读到的一致
        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        /**
         * 5. 补充正文：本版本只接受正文，附件属完整版 backlog 第 2 项（用户 2026-10-06 裁决）。
         *
         * <p><strong>刻意不判定"是否已过 `action_deadline_at`"</strong>：本版本没有超时自动关闭
         * （自动任务属 backlog 第 3 项），期限只用于界面展示。若在这里拒绝过期提交，等于把
         * "系统还没实现自动关闭"变成"员工连信息都补不进来"，比不做更糟。</p>
         */
        String content = command.content();
        if (content == null || content.isEmpty() || content.length() > MAX_CONTENT_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "补充内容长度必须在 1 到 10000 之间");
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 6. 条件更新：清空补充期限、回到处理中；assignee_id 不动（补充不等于换人）
        int updatedRows = ticketMapper.supplement(
                visible.getId(),
                command.version(),
                actorId,
                now);

        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());
            if (current == null) {
                throw new IllegalStateException("提交补充信息冲突后无法读取工单快照");
            }
            throw conflict(current.getVersion(), current.getStatus());
        }

        Ticket updated = ticketMapper.selectById(visible.getId());
        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("提交补充信息后无法读取工单快照");
        }

        // 7. 不可变时间线：补充正文与状态迁移；期限已失效，因此不写 deadline_at
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("REQUESTER_SUPPLEMENT");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setContent(content);
        record.setFromStatus(WAITING_FOR_REQUESTER);
        record.setToStatus(PROCESSING);
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("补充记录插入失败");
        }

        // 8. 负责人没变、期限已清空，摘要直接取本事务读到的可见快照
        TicketUserSummaryResult assignee = new TicketUserSummaryResult(
                visible.getAssigneeId(),
                visible.getAssigneeDisplayName());

        return new TicketActionResult(
                updated.getTicketNo(),
                PROCESSING,
                assignee,
                null,
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }


    /** 创建工单 */
    private TicketCreatedResult createTicket(CreateTicketCommand command, Long requesterId, String submissionKey) {
        //1.校验分类
        if (!categoryAvailabilityPort.isEnabled(command.categoryId())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "请选择启用的分类");
        }

        // 2. 同一时刻分别用于 UTC 存储和北京时间编号。
        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);

        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        LocalDate businessDate = instant.atZone(BUSINESS_ZONE).toLocalDate();

        //3.创建并领取当天序号
        ticketDailySequenceMapper.increment(businessDate);

        Long sequence = ticketDailySequenceMapper.selectCurrentForUpdate(businessDate);

        if(sequence ==null || sequence < 1){
            throw new IllegalStateException("工单每日序号分配失败");
        }

        //4.创建编号
        String ticketNo = "FD-"
                + businessDate.format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-"
                + String.format(Locale.ROOT, "%03d", sequence);

        //5.创建工单
        Ticket ticket = new Ticket();
        ticket.setTicketNo(ticketNo);
        ticket.setSubmissionKey(submissionKey);
        ticket.setRequesterId(requesterId);
        ticket.setTitle(command.title());
        ticket.setDescription(command.description());
        ticket.setCategoryId(command.categoryId());
        ticket.setPriority(command.priority());
        ticket.setStatus(PENDING);
        ticket.setRecordSeq(1);
        ticket.setVersion(0L);
        ticket.setCreatedAt(now);
        ticket.setUpdatedAt(now);

        if (ticketMapper.insert(ticket) != 1) {
            throw new IllegalStateException("工单插入失败");
        }

        //6.创建工单记录
        TicketRecord record = new TicketRecord();
        record.setTicketId(ticket.getId());
        record.setSequenceNo(1);
        record.setRecordType("CREATE");
        record.setActorType("USER");
        record.setActorUserId(requesterId);
        record.setToStatus(PENDING);
        record.setToCategoryId(command.categoryId());
        record.setToPriority(command.priority());
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("创建记录插入失败");
        }

        return toCreatedResult(ticket);
    }

    /**始终返回首次创建时的结果，不使用工单当前可变状态。*/
    private TicketCreatedResult toCreatedResult(Ticket ticket) {
        return new TicketCreatedResult(
                ticket.getTicketNo(),
                PENDING,
                0L,
                ticket.getCreatedAt().atOffset(ZoneOffset.UTC));
    }

    /** 冲突响应统一携带当前快照，供前端刷新后重试。 */
    private ApiException conflict(Long version, String status) {
        return new ApiException(
                HttpStatus.CONFLICT,
                "TICKET_CONFLICT",
                "工单状态或版本已变化，请刷新后重试",
                version,
                status);
    }
}
