package com.flowdesk.ticket.application.service.impl;

import com.flowdesk.common.exception.ApiException;
import com.flowdesk.ticket.application.command.ClaimTicketCommand;
import com.flowdesk.ticket.application.command.CreateTicketCommand;
import com.flowdesk.ticket.application.port.CategoryAvailabilityPort;
import com.flowdesk.ticket.application.port.CurrentRequesterPort;
import com.flowdesk.ticket.application.port.TicketClaimantPort;
import com.flowdesk.ticket.application.port.TicketReadPermissionPort;
import com.flowdesk.ticket.application.result.TicketClaimResult;
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
            TicketParticipantMapper ticketParticipantMapper) {
        this.clock = clock;
        this.ticketMapper = ticketMapper;
        this.ticketDailySequenceMapper = ticketDailySequenceMapper;
        this.ticketRecordMapper = ticketRecordMapper;
        this.currentRequesterPort = currentRequesterPort;
        this.categoryAvailabilityPort = categoryAvailabilityPort;
        this.ticketReadPermissionPort = ticketReadPermissionPort;
        this.ticketClaimPort = ticketClaimPort;
        this.ticketParticipantMapper = ticketParticipantMapper;

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

        //2.处理用户submissionKey
        String submissionKey = UUID.fromString(command.submissionKey()).toString();

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
            //excute抛出异常时，创建事务已经回滚
            //使用新的事务查询并发请求提交的原工单
            Ticket existing = transactionTemplate.execute(transactionStatus -> {
                return ticketMapper.selectCreationByRequesterAndSubmissionKey(requesterId, submissionKey);
            });

            //
            if (existing != null)
                return toCreatedResult(existing);

            //没有对应的提交结果，不能将编号等其他冲突当作成功
            throw exception;

        }
    }

    /** 领取工单：权限、身份、资源关系与并发条件都在服务端复核。 */
    @Override
    @Transactional
    public TicketClaimResult claim(String ticketNo, ClaimTicketCommand command) {
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
                || !command.version().equals(visible.getVersion())) {
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

        return new TicketClaimResult(
                updated.getTicketNo(),
                "PROCESSING",
                claimant,
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

    /**
     * 始终返回首次创建时的结果，不使用工单当前可变状态。
     */
    private TicketCreatedResult toCreatedResult(Ticket ticket) {
        return new TicketCreatedResult(
                ticket.getTicketNo(),
                PENDING,
                0L,
                ticket.getCreatedAt().atOffset(ZoneOffset.UTC));
    }
}
