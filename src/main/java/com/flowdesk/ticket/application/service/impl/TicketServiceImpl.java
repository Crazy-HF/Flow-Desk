package com.flowdesk.ticket.application.service.impl;

import com.flowdesk.common.exception.ApiException;
import com.flowdesk.ticket.application.command.CreateTicketCommand;
import com.flowdesk.ticket.application.port.CategoryAvailabilityPort;
import com.flowdesk.ticket.application.port.CurrentRequesterPort;
import com.flowdesk.ticket.application.result.TicketCreatedResult;
import com.flowdesk.ticket.application.service.TicketService;
import com.flowdesk.ticket.domain.Ticket;
import com.flowdesk.ticket.domain.TicketRecord;
import com.flowdesk.ticket.mapper.TicketMapper;
import com.flowdesk.ticket.mapper.TicketDailySequenceMapper;
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
    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("Asia/Shanghai");

    private final Clock clock;
    private final TicketMapper ticketMapper;
    private final TicketDailySequenceMapper ticketDailySequenceMapper;
    private final TicketRecordMapper ticketRecordMapper;
    private final CurrentRequesterPort currentRequesterPort;
    private final CategoryAvailabilityPort categoryAvailabilityPort;
    private final TransactionTemplate transactionTemplate;

    public TicketServiceImpl(
            Clock clock,
            TicketMapper ticketMapper,
            TicketDailySequenceMapper ticketDailySequenceMapper,
            TicketRecordMapper ticketRecordMapper,
            CurrentRequesterPort currentRequesterPort,
            CategoryAvailabilityPort categoryAvailabilityPort,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.ticketMapper = ticketMapper;
        this.ticketDailySequenceMapper = ticketDailySequenceMapper;
        this.ticketRecordMapper = ticketRecordMapper;
        this.currentRequesterPort = currentRequesterPort;
        this.categoryAvailabilityPort = categoryAvailabilityPort;

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
