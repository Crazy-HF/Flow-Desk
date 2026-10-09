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
import com.flowdesk.ticket.domain.TicketPriority;
import com.flowdesk.ticket.domain.TicketRecord;
import com.flowdesk.ticket.infrastructure.persistence.TicketDetailRow;
import com.flowdesk.ticket.infrastructure.persistence.TicketDuplicateTargetRow;
import com.flowdesk.ticket.mapper.TicketMapper;
import com.flowdesk.ticket.mapper.TicketDailySequenceMapper;
import com.flowdesk.ticket.mapper.TicketParticipantMapper;
import com.flowdesk.ticket.mapper.TicketRecordMapper;
import com.flowdesk.ticket.mapper.TicketRelationMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
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

    /** 转交权限由动态 RBAC 授予；与处理权限分开，可以单独授予一个角色。 */
    private static final String TICKET_TRANSFER = "TICKET_TRANSFER";

    /** 关闭权限由动态 RBAC 授予；关闭是结束工单的处置动作，必须同时具备处理权限。 */
    private static final String TICKET_CLOSE = "TICKET_CLOSE";

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
    /** 工单有向关联（ticket_relation）：关闭为「重复工单」时写入一条指向有效工单的关联。 */
    private final TicketRelationMapper ticketRelationMapper;
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
            TicketRelationMapper ticketRelationMapper,
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
        this.ticketRelationMapper = ticketRelationMapper;
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

    /** 当前负责人调整分类：状态、负责人与期限都不变，只替换分类并留下调整记录。 */
    @Override
    @Transactional
    public TicketActionResult changeCategory(String ticketNo, ChangeCategoryCommand command) {
        //1.身份、处理权限
        long actorId = currentRequesterPort.currentUserId();

        if (!ticketReadPermissionPort.hasAuthority(TICKET_PROCESS)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "无处理权限");
        }

        //2.可见性：无权查看与编号不存在统一 404
        TicketDetailRow visible = ticketMapper.selectVisibleDetail(
                ticketNo,
                actorId,
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_OWN"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_QUEUE"),
                ticketReadPermissionPort.hasAuthority("TICKET_VIEW_PARTICIPATED"));

        // 读不到就是不可见或不存在：先按 404 收口，不能把 null 带到下面的 getStatus()
        if (visible == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }

        // 3. 「处理中」与「待补充」的当前负责人可以调整分类；状态或身份不符都按冲突返回
        if (!isAdjustableStatus(visible.getStatus())
                || visible.getAssigneeId() == null
                || visible.getAssigneeId() != actorId) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 4. 版本必须与客户端读到的一致；409 先于字段 400，避免客户端拿旧版本反复试字段
        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 5. 原因必填；null 必须在 isEmpty 之前判掉（非 HTTP 调用方不走 Bean Validation）
        String reason = command.reason();
        if (reason == null || reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "调整原因长度必须在 1 到 1000 之间");
        }

        // 6. 目标分类必须存在且启用；停用分类不能再被选为当前分类
        if (!categoryAvailabilityPort.isEnabled(command.categoryId())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "请选择启用的分类");
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 7. 条件更新是唯一胜者判定：版本、状态与负责人都写在 WHERE 里
        int updatedRows = ticketMapper.changeCategory(
                visible.getId(),
                command.version(),
                actorId,
                command.categoryId(),
                now);

        //更新分类失败时，锁定读当前工单快照，并抛出异常
        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());
            if (current == null) {
                throw new IllegalStateException("调整分类冲突后无法读取工单快照");
            }
            throw conflict(current.getVersion(), current.getStatus());
        }

        //更新成功后，根据Id读取当前工单最新数据
        Ticket updated = ticketMapper.selectById(visible.getId());
        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("调整分类后无法读取工单快照");
        }

        // 8. 写入工单转变记录
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("CATEGORY_CHANGE");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setReason(reason);
        record.setFromCategoryId(visible.getCategoryId());
        record.setToCategoryId(command.categoryId());
        record.setFromStatus(visible.getStatus());
        record.setToStatus(visible.getStatus());
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("分类调整记录插入失败");
        }

        // 9. 状态与负责人没变，摘要取本事务读到的可见快照；「待补充」时期限仍然有效，原样返回
        TicketUserSummaryResult assignee = new TicketUserSummaryResult(
                visible.getAssigneeId(),
                visible.getAssigneeDisplayName());

        return new TicketActionResult(
                updated.getTicketNo(),
                visible.getStatus(),
                assignee,
                toOffsetDateTime(visible.getActionDeadlineAt()),
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }

    /** 当前负责人调整优先级：与调整分类同一条门禁，只替换优先级。 */
    @Override
    @Transactional
    public TicketActionResult changePriority(String ticketNo, ChangePriorityCommand command) {
        // 1. 身份与处理权限：优先级调整与处理记录同属 TICKET_PROCESS
        long actorId = currentRequesterPort.currentUserId();
        if (!ticketReadPermissionPort.hasAuthority(TICKET_PROCESS)) {
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

        // 3. 「处理中」与「待补充」的当前负责人可以调整优先级
        if (!isAdjustableStatus(visible.getStatus())
                || visible.getAssigneeId() == null
                || visible.getAssigneeId() != actorId) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 4. 版本必须与客户端读到的一致
        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 5. 原因必填
        String reason = command.reason();
        if (reason == null || reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "调整原因长度必须在 1 到 1000 之间");
        }

        // 6. 优先级取值以枚举为准；非 HTTP 调用方不走 @Pattern，这里兜底（null 也算非法）
        if (!isValidPriority(command.priority())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "优先级必须是LOW、MEDIUM或HIGH");
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 7. 条件更新是唯一胜者判定
        int updatedRows = ticketMapper.changePriority(
                visible.getId(),
                command.version(),
                actorId,
                command.priority(),
                now);

        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());
            if (current == null) {
                throw new IllegalStateException("调整优先级冲突后无法读取工单快照");
            }
            throw conflict(current.getVersion(), current.getStatus());
        }

        Ticket updated = ticketMapper.selectById(visible.getId());
        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("调整优先级后无法读取工单快照");
        }

        // 8. 不可变时间线：原优先级 → 新优先级 + 原因；状态没变，两侧写同一个状态
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("PRIORITY_CHANGE");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setReason(reason);
        record.setFromPriority(visible.getPriority());
        record.setToPriority(command.priority());
        record.setFromStatus(visible.getStatus());
        record.setToStatus(visible.getStatus());
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("优先级调整记录插入失败");
        }

        // 9. 状态与负责人没变，摘要取本事务读到的可见快照
        TicketUserSummaryResult assignee = new TicketUserSummaryResult(
                visible.getAssigneeId(),
                visible.getAssigneeDisplayName());

        return new TicketActionResult(
                updated.getTicketNo(),
                visible.getStatus(),
                assignee,
                toOffsetDateTime(visible.getActionDeadlineAt()),
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }

    /** 当前负责人转交工单：与调整分类同一条门禁，只替换负责人。 */
    @Override
    @Transactional
    public TicketActionResult transfer(String ticketNo, TransferCommand command) {
        // 1. 身份与转交权限：转交单独要求 TICKET_TRANSFER，与处理权限分开（docs/api-design.md 9）
        long actorId = currentRequesterPort.currentUserId();
        if (!ticketReadPermissionPort.hasAuthority(TICKET_TRANSFER)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "无转交工单权限");
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

        // 3. 「处理中」与「待补充」的当前负责人可以转交
        if (!isAdjustableStatus(visible.getStatus())
                || visible.getAssigneeId() == null
                || visible.getAssigneeId() != actorId) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 4. 版本必须与客户端读到的一致
        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 5. 原因必填
        String reason = command.reason();
        if (reason == null || reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "转交原因长度必须在 1 到 1000 之间");
        }

        // 6. 字段级错误先于加锁与条件更新：转给自己、转给提交人都是无效请求。
        Long newAssigneeId = command.newAssigneeId();
        if (newAssigneeId == null || newAssigneeId == actorId) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "不能转交给当前负责人自己");
        }

        if (Objects.equals(newAssigneeId, visible.getRequesterId())) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "不能转交给工单提交人");
        }

        // 7. 固定锁顺序第一段：先锁工单行，再锁用户行。
        //    所有工单动作都以工单行的写锁起手；反过来先锁 iam_user 时，另一个动作持有工单行锁后会因为
        //    ticket_record.actor_user_id 的外键校验去申请同一行 iam_user 的共享锁，两条路径首尾相接成环，
        //    InnoDB 回滚其中一个，调用方拿到的是 500 而不是可重试的 409。
        Ticket locked = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());
        if (locked == null) {
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }

        // 拿到工单行锁后复核快照：version 是这一行的变更计数，领取、转交、任何状态迁移都会 +1，
        // 因此 version 未变即证明前面读到的状态、负责人与提交人都还是当前值
        if (!Objects.equals(locked.getVersion(), visible.getVersion())) {
            throw conflict(locked.getVersion(), locked.getStatus());
        }

        // 8. 固定锁顺序第二段：无论谁转给谁，一律按 user_id 升序锁「当前负责人 + 新负责人」两行。
        //    互转并发（A 转给 B、B 转给 A）时两边以同一顺序取锁，不会形成 AB-BA 死锁
        long firstUserId = Math.min(actorId, newAssigneeId);
        long secondUserId = Math.max(actorId, newAssigneeId);

        TicketUserSummaryResult firstLocked = ticketClaimPort.lockEligibleClaimant(firstUserId);
        TicketUserSummaryResult secondLocked = ticketClaimPort.lockEligibleClaimant(secondUserId);

        TicketUserSummaryResult actorLocked =
                actorId == firstUserId ? firstLocked : secondLocked;
        TicketUserSummaryResult newAssignee =
                newAssigneeId == firstUserId ? firstLocked : secondLocked;

        // 9. 锁住之后再复核双方资格，并发的角色或账号变更由这次复核收敛
        if (actorLocked == null) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "当前用户不能转交工单");
        }

        if (newAssignee == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "新负责人必须是启用的 IT 支持人员");
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 10. 条件更新是唯一胜者判定：版本、状态、原负责人与"新负责人不是提交人"都在 WHERE 里；
        //    status 与 action_deadline_at 都不写，转交不产生中间态
        int updatedRows = ticketMapper.transfer(
                visible.getId(),
                command.version(),
                actorId,
                newAssigneeId,
                now);

        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());
            if (current == null) {
                throw new IllegalStateException("转交冲突后无法读取工单快照");
            }
            throw conflict(current.getVersion(), current.getStatus());
        }

        Ticket updated = ticketMapper.selectById(visible.getId());
        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("转交后无法读取工单快照");
        }

        // 11. 记录新负责人的参与关系：与领取一致，转交后他才能看到这张工单
        ticketParticipantMapper.recordAssignment(
                updated.getId(),
                newAssigneeId,
                now);

        // 12. 不可变时间线：原负责人 → 新负责人 + 原因；状态没变，两侧写同一个状态
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("TRANSFER");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setReason(reason);
        record.setFromAssigneeId(visible.getAssigneeId());
        record.setToAssigneeId(newAssigneeId);
        record.setFromStatus(visible.getStatus());
        record.setToStatus(visible.getStatus());
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("转交记录插入失败");
        }

        // 13. 摘要里的负责人是新负责人；「待补充」时转交，期限原样有效，一并返回
        return new TicketActionResult(
                updated.getTicketNo(),
                visible.getStatus(),
                newAssignee,
                toOffsetDateTime(visible.getActionDeadlineAt()),
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }

    /**
     * 当前负责人手动关闭工单：只有「处理中」可以关闭，进入终态「已关闭」。
     */
    @Override
    @Transactional
    public TicketActionResult close(String ticketNo, CloseTicketCommand command) {
        //1.身份、权限
        // 必须是基本类型：下面用 `visible.getAssigneeId() != actorId` 判定"本人是负责人"，
        // 若这里写成 Long，两侧就是 Long 与 Long 的引用比较（超出 Long 缓存范围的用户 ID 会被误判成 409）
        long actorId = currentRequesterPort.currentUserId();

        if (!ticketReadPermissionPort.hasAuthority(TICKET_PROCESS)
                || !ticketReadPermissionPort.hasAuthority(TICKET_CLOSE)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "无关闭工单权限");
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

        // 3. 只有「处理中」的当前负责人可以关闭
        if (!PROCESSING.equals(visible.getStatus())
                || visible.getAssigneeId() == null
                || visible.getAssigneeId() != actorId) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 4. 版本必须与客户端读到的一致
        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 5. 字段校验先于条件更新：原因、说明与「重复工单」的跨字段规则
        String reasonCode = command.reasonCode();
        if (!"DUPLICATE".equals(reasonCode)
                && !"OUT_OF_SCOPE".equals(reasonCode)
                && !"INVALID".equals(reasonCode)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "关闭原因必须是 DUPLICATE、OUT_OF_SCOPE 或 INVALID");
        }

        String description = command.description();
        if (description == null || description.isEmpty() || description.length() > MAX_REASON_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "关闭说明长度必须在 1 到 1000 之间");
        }

        boolean duplicate = "DUPLICATE".equals(reasonCode);
        String duplicateTicketNo = command.duplicateTicketNo();

        if (!duplicate && duplicateTicketNo != null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "只有关闭原因为重复工单时才能填写重复工单编号");
        }


        // 6. 重复原因必须解析到有效目标：存在、同一提交人、非自身、状态不是已取消/已关闭。
        //    目标属于别人与目标不存在都得到 null，统一 400，不向调用方回显他人工单是否存在。
        TicketDuplicateTargetRow duplicateTarget = null;
        if(duplicate){
            //重复工单是判断是否填写重复工单号
            if(duplicateTicketNo == null || duplicateTicketNo.isEmpty()){
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_FAILED",
                        "关闭原因为重复工单时必须填写重复工单编号");
            }

            //获取重复工单
            duplicateTarget = ticketMapper.selectDuplicateTarget(
                    visible.getId(),
                    visible.getRequesterId(),
                    duplicateTicketNo);

            if (duplicateTarget == null) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_FAILED",
                        "重复工单编号无效");
            }
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 7. 条件更新是唯一胜者判定：版本、状态与负责人都在 WHERE 里；
        //    状态、关闭字段与 ended_at 写在同一句，拆开就会撞 ck_ticket_status_ended
        int updatedRows = ticketMapper.closeManually(
                visible.getId(),
                command.version(),
                actorId,
                reasonCode,
                now);

        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());
            if (current == null) {
                throw new IllegalStateException("关闭冲突后无法读取工单快照");
            }
            throw conflict(current.getVersion(), current.getStatus());
        }

        Ticket updated = ticketMapper.selectById(visible.getId());
        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("关闭后无法读取工单快照");
        }

        // 8. 只有重复关闭才写工单关联：source = 被关闭的本单，target = 有效的那张。
        //    唯一键 uk_ticket_relation_source_type 保证一张单只有一条重复指向；
        //    「重复关闭」本身已被第 7 步的条件更新挡住，不会走到唯一键冲突
        if (duplicateTarget != null) {
            if (ticketRelationMapper.recordDuplicate(
                    updated.getId(),
                    duplicateTarget.getId(),
                    actorId,
                    now) != 1) {
                throw new IllegalStateException("重复工单关联写入失败");
            }
        }

        // 9. 不可变时间线：关闭方式与标准原因随记录留存，供「三条终态可区分」使用
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("CLOSURE");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setReason(description);
        record.setCloseMethod("MANUAL");
        record.setCloseReason(reasonCode);
        record.setFromStatus(PROCESSING);
        record.setToStatus("CLOSED");
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("关闭记录插入失败");
        }

        // 10. 终态期限已失效；负责人按快照保留（ck_ticket_status_assignee 允许「已关闭」带负责人）
        TicketUserSummaryResult assignee = new TicketUserSummaryResult(
                visible.getAssigneeId(),
                visible.getAssigneeDisplayName());

        return new TicketActionResult(
                updated.getTicketNo(),
                "CLOSED",
                assignee,
                null,
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }

    /**
     * 提交人发起撤销请求：三种「有人负责且未终结」的状态都可以发起，工单状态不变
     * （`docs/kickoff.md` 4.7 未来方向、`docs/implementation-plan.md` 9.3 决策记录）。
     *
     * <p>只发起不迁移状态：待受理本来就可以直接取消，那里没有负责人需要同意；
     * 一旦有人负责，单方面终止就要先取得当前负责人同意，因此工单停在原状态等待批准。</p>
     */
    @Override
    @Transactional
    public TicketActionResult requestCancel(String ticketNo, RequestCancelCommand command) {
        // 1. 身份与提交人动作权限：与补充、确认、未解决反馈、直接撤销共用 TICKET_REQUESTER_ACTION
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

        if(visible == null){
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }

        // 3. 三个"有人负责"的状态 + 本人是提交人；已有待决请求也按冲突返回——
        //    重复发起不是幂等成功：前一次的说明还在，不能被静默覆盖
        if (!isCancelRequestableStatus(visible.getStatus())
                || visible.getRequesterId() == null
                || visible.getRequesterId() != actorId
                || visible.getCancelRequestedAt() != null) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 4. 版本必须与客户端读到的一致
        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 5. 必须写清为什么撤销；null 必须在 isEmpty 之前判掉（非 HTTP 调用方不走 Bean Validation）
        String reason = command.reason();
        if (reason == null || reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "撤销请求说明长度必须在 1 到 1000 之间");
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 6. 响应期限从发起时刻起算；只落库展示，到期不自动处置（与补充期限同口径）
        LocalDateTime deadlineAt = now.plus(ticketProperties.cancelRequestWindow());

        // 7. 条件更新是唯一胜者判定：版本、提交人、三种状态与"当前没有待决请求"都在 WHERE 里
        int updatedRows = ticketMapper.requestCancel(
                visible.getId(),
                command.version(),
                actorId,
                reason,
                deadlineAt,
                now);

        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());
            if (current == null) {
                throw new IllegalStateException("发起撤销请求冲突后无法读取工单快照");
            }
            throw conflict(current.getVersion(), current.getStatus());
        }

        Ticket updated = ticketMapper.selectById(visible.getId());
        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("发起撤销请求后无法读取工单快照");
        }

        // 8. 不可变时间线：请求说明与响应期限随记录留存；状态没有变化，from 与 to 相同
        //    （既有 PROCESS / CATEGORY_CHANGE / TRANSFER 等不改变状态的动作也是这么写的）
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("CANCELLATION_REQUEST");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setReason(reason);
        record.setDeadlineAt(deadlineAt);
        record.setFromStatus(visible.getStatus());
        record.setToStatus(visible.getStatus());
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("撤销请求记录插入失败");
        }

        // 9. 状态、负责人与工单自身的期限都不变；摘要里的期限仍是工单的期限，
        //    不是撤销请求的响应期限（后者只通过详情返回）
        TicketUserSummaryResult assignee = visible.getAssigneeId() == null
                ? null
                : new TicketUserSummaryResult(
                visible.getAssigneeId(),
                visible.getAssigneeDisplayName());

        return new TicketActionResult(
                updated.getTicketNo(),
                visible.getStatus(),
                assignee,
                toOffsetDateTime(visible.getActionDeadlineAt()),
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }

    /**
     * 当前负责人批准撤销请求：工单进入终态「已取消」。
     */
    @Override
    @Transactional
    public TicketActionResult approveCancel(String ticketNo, ApproveCancelCommand command) {
        // 1. 身份与处理权限：批准与拒绝撤销请求共用 TICKET_PROCESS。
        //    刻意不要求 TICKET_CLOSE——撤销不是关闭，两者的语义与后果都不同
        long actorId = currentRequesterPort.currentUserId();

        if (!ticketReadPermissionPort.hasAuthority(TICKET_PROCESS)) {
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

        if(visible == null){
            throw new ApiException(
                    HttpStatus.NOT_FOUND,
                    "TICKET_NOT_FOUND",
                    "工单不存在");
        }

        // 3. 必须是当前负责人，且工单上确实挂着一个待决请求。
        //    "没有请求"与"请求已经被别人处理掉"都落到这里，统一 409 带快照
        if (!isCancelRequestableStatus(visible.getStatus())
                || visible.getAssigneeId() == null
                || visible.getAssigneeId() != actorId
                || visible.getCancelRequestedAt() == null) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 4. 版本必须与客户端读到的一致
        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 5. 条件更新是唯一胜者判定。注意这条 SQL 里同时处理了三件事：
        //    进入终态要写 ended_at、要清 action_deadline_at（待补充/待确认带着期限）、
        //    还要清请求三列（终态不允许挂待决请求）——三者必须在同一条 UPDATE 内
        int updatedRows = ticketMapper.approveCancel(
                visible.getId(),
                command.version(),
                actorId,
                now);

        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());
            if (current == null) {
                throw new IllegalStateException("批准撤销请求冲突后无法读取工单快照");
            }
            throw conflict(current.getVersion(), current.getStatus());
        }

        Ticket updated = ticketMapper.selectById(visible.getId());
        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("批准撤销请求后无法读取工单快照");
        }

        // 6. 时间线用独立的记录类型：批准、拒绝、撤回、直接取消四者可区分
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("CANCELLATION_APPROVED");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setFromStatus(visible.getStatus());
        record.setToStatus("CANCELED");
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("批准撤销记录插入失败");
        }

        // 7. 终态期限已失效；负责人按快照保留（ck_ticket_status_assignee 允许「已取消」带负责人）
        TicketUserSummaryResult assignee = new TicketUserSummaryResult(
                visible.getAssigneeId(),
                visible.getAssigneeDisplayName());

        return new TicketActionResult(
                updated.getTicketNo(),
                "CANCELED",
                assignee,
                null,
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }

    /**
     * 当前负责人拒绝撤销请求：工单保留原状态与期限，请求失效。
     */
    @Override
    @Transactional
    public TicketActionResult rejectCancel(String ticketNo, RejectCancelCommand command) {
        // 1. 身份与处理权限：与批准同一条判定
        long actorId = currentRequesterPort.currentUserId();

        if (!ticketReadPermissionPort.hasAuthority(TICKET_PROCESS)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "无处理工单权限");
        }

        // 2. 可见性
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

        // 3. 必须是当前负责人，且确实有请求可以拒绝
        if (!isCancelRequestableStatus(visible.getStatus())
                || visible.getAssigneeId() == null
                || visible.getAssigneeId() != actorId
                || visible.getCancelRequestedAt() == null) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 4. 版本必须一致
        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 5. 拒绝必须说明理由；长度上限与其它原因类动作一致
        String reason = command.reason();
        if (reason == null || reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "拒绝原因长度必须在 1 到 1000 之间");
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 6. 条件更新是唯一胜者判定：状态与期限都不变，只让请求失效
        int updatedRows = ticketMapper.rejectCancel(
                visible.getId(),
                command.version(),
                actorId,
                now);

        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());
            if (current == null) {
                throw new IllegalStateException("拒绝撤销请求冲突后无法读取工单快照");
            }
            throw conflict(current.getVersion(), current.getStatus());
        }

        Ticket updated = ticketMapper.selectById(visible.getId());
        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("拒绝撤销请求后无法读取工单快照");
        }

        // 7. 时间线：拒绝原因必须留存，否则提交人只看到"被拒绝"而无从调整
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("CANCELLATION_REJECTED");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setReason(reason);
        record.setFromStatus(visible.getStatus());
        record.setToStatus(visible.getStatus());
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("拒绝撤销记录插入失败");
        }

        // 8. 状态与期限都不变，摘要原样返回工单当前快照
        TicketUserSummaryResult assignee = new TicketUserSummaryResult(
                visible.getAssigneeId(),
                visible.getAssigneeDisplayName());

        return new TicketActionResult(
                updated.getTicketNo(),
                visible.getStatus(),
                assignee,
                toOffsetDateTime(visible.getActionDeadlineAt()),
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }

    /**
     * 提交人撤回自己的撤销请求：工单保留原状态与期限，请求失效（设计点⑤）。
     */
    @Override
    @Transactional
    public TicketActionResult withdrawCancelRequest(
            String ticketNo, WithdrawCancelRequestCommand command) {
        // 1. 身份与提交人动作权限
        long actorId = currentRequesterPort.currentUserId();

        if (!ticketReadPermissionPort.hasAuthority(TICKET_REQUESTER_ACTION)) {
            throw new ApiException(
                    HttpStatus.FORBIDDEN,
                    "TICKET_ACTION_FORBIDDEN",
                    "无提交人操作权限");
        }

        // 2. 可见性
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

        // 3. 只有提交人本人能撤回自己的请求；负责人撤不掉（即使他同时持有提交人权限）
        if (!isCancelRequestableStatus(visible.getStatus())
                || visible.getRequesterId() == null
                || visible.getRequesterId() != actorId
                || visible.getCancelRequestedAt() == null) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 4. 版本必须一致
        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 5. 条件更新是唯一胜者判定
        int updatedRows = ticketMapper.withdrawCancelRequest(
                visible.getId(),
                command.version(),
                actorId,
                now);

        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());
            if (current == null) {
                throw new IllegalStateException("撤回撤销请求冲突后无法读取工单快照");
            }
            throw conflict(current.getVersion(), current.getStatus());
        }

        Ticket updated = ticketMapper.selectById(visible.getId());
        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("撤回撤销请求后无法读取工单快照");
        }

        // 6. 时间线不写原因：撤回是"我改主意了"，请求里原本的说明仍在 CANCELLATION_REQUEST 上
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("CANCELLATION_REQUEST_WITHDRAWN");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setFromStatus(visible.getStatus());
        record.setToStatus(visible.getStatus());
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("撤回撤销请求记录插入失败");
        }

        // 7. 状态与期限都不变
        TicketUserSummaryResult assignee = visible.getAssigneeId() == null
                ? null
                : new TicketUserSummaryResult(
                visible.getAssigneeId(),
                visible.getAssigneeDisplayName());

        return new TicketActionResult(
                updated.getTicketNo(),
                visible.getStatus(),
                assignee,
                toOffsetDateTime(visible.getActionDeadlineAt()),
                updated.getVersion(),
                instant.atOffset(ZoneOffset.UTC));
    }

    /**
     * 提交人直接撤销自己的工单，进入终态「已取消」（`docs/kickoff.md` 4.7）。
     *
     * <p><b>2026-10-08 规则变更</b>：只剩「待受理」可以这样撤销——那里没有负责人，
     * 没有人需要批准。处理中、待补充、待确认三种状态改走两阶段：提交人
     * {@link #requestCancel}，当前负责人 {@link #approveCancel} / {@link #rejectCancel}。</p>
     */
    @Override
    @Transactional
    public TicketActionResult cancel(String ticketNo, CancelTicketCommand command) {
        // 1. 身份与提交人动作权限：撤销与补充、确认、未解决反馈共用 TICKET_REQUESTER_ACTION
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

        // 3. 只剩「待受理」能直接撤销（2026-10-08 规则变更）：那里没有负责人，没有人需要批准；
        //    其余非终态改走两阶段，终态与「不是提交人」都按冲突返回
        if (!isDirectCancelableStatus(visible.getStatus())
                || visible.getRequesterId() == null
                || visible.getRequesterId() != actorId) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 4. 版本必须与客户端读到的一致
        if (!Objects.equals(command.version(), visible.getVersion())) {
            throw conflict(visible.getVersion(), visible.getStatus());
        }

        // 5. 原因必填
        String reason = command.reason();
        if (reason == null || reason.isEmpty() || reason.length() > MAX_REASON_LENGTH) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "撤销原因长度必须在 1 到 1000 之间");
        }

        Instant instant = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime now = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);

        // 6. 条件更新是唯一胜者判定：版本、提交人与「待受理」这个状态都在 WHERE 里
        int updatedRows = ticketMapper.cancel(
                visible.getId(),
                command.version(),
                actorId,
                now);

        if (updatedRows != 1) {
            Ticket current = ticketMapper.selectClaimConflictSnapshotForUpdate(visible.getId());
            if (current == null) {
                throw new IllegalStateException("撤销冲突后无法读取工单快照");
            }
            throw conflict(current.getVersion(), current.getStatus());
        }

        Ticket updated = ticketMapper.selectById(visible.getId());
        if (updated == null
                || updated.getRecordSeq() == null
                || updated.getVersion() == null) {
            throw new IllegalStateException("撤销后无法读取工单快照");
        }

        // 7. 不可变时间线：撤销原因只存在于记录里（ticket 表没有撤销原因列）。
        //    记录类型仍是 CANCELLATION——它与 CANCELLATION_APPROVED 的区别正是"没有负责人需要批准"
        TicketRecord record = new TicketRecord();
        record.setTicketId(updated.getId());
        record.setSequenceNo(updated.getRecordSeq());
        record.setRecordType("CANCELLATION");
        record.setActorType("USER");
        record.setActorUserId(actorId);
        record.setReason(reason);
        record.setFromStatus(visible.getStatus());
        record.setToStatus("CANCELED");
        record.setCreatedAt(now);

        if (ticketRecordMapper.insert(record) != 1) {
            throw new IllegalStateException("撤销记录插入失败");
        }

        // 8. 终态期限已失效；待受理本来没有负责人，其余状态保留最后负责人
        TicketUserSummaryResult assignee = visible.getAssigneeId() == null
                ? null
                : new TicketUserSummaryResult(
                visible.getAssigneeId(),
                visible.getAssigneeDisplayName());

        return new TicketActionResult(
                updated.getTicketNo(),
                "CANCELED",
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

    /** 「处理中」与「待补充」：当前负责人可调整分类、优先级并转交的两个状态。 */
    private boolean isAdjustableStatus(String status) {
        return PROCESSING.equals(status) || WAITING_FOR_REQUESTER.equals(status);
    }

    /** 「待受理」：没有人负责，撤销不需要任何人批准，提交人可以直接取消。 */
    private boolean isDirectCancelableStatus(String status) {
        return PENDING.equals(status);
    }

    /**
     * 三种「有人负责且未终结」的状态：撤销必须走两阶段，提交人只能发起请求。
     *
     * <p>与 ck_ticket_cancel_request_status 的状态白名单逐字一致——两边不同步时数据库会直接拒绝写入。</p>
     */
    private boolean isCancelRequestableStatus(String status) {
        return PROCESSING.equals(status)
                || WAITING_FOR_REQUESTER.equals(status)
                || WAITING_FOR_CONFIRMATION.equals(status);
    }

    /** 优先级取值以 {@link TicketPriority} 为准，避免再抄一份正则；null 视为非法。 */
    private boolean isValidPriority(String priority) {
        if (priority == null) {
            return false;
        }

        for (TicketPriority candidate : TicketPriority.values()) {
            if (candidate.name().equals(priority)) {
                return true;
            }
        }

        return false;
    }

    /** 快照里的期限按 UTC 输出；只有待补充与待确认有值，其他状态为 null。 */
    private OffsetDateTime toOffsetDateTime(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
