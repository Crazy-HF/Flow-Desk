package com.flowdesk.ticket.application.service.impl;

import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.ticket.application.command.AddProcessingRecordCommand;
import com.flowdesk.ticket.application.command.ApproveCancelCommand;
import com.flowdesk.ticket.application.command.CancelTicketCommand;
import com.flowdesk.ticket.application.command.ChangeCategoryCommand;
import com.flowdesk.ticket.application.command.ChangePriorityCommand;
import com.flowdesk.ticket.application.command.ClaimTicketCommand;
import com.flowdesk.ticket.application.command.CloseTicketCommand;
import com.flowdesk.ticket.application.command.ConfirmResolutionCommand;
import com.flowdesk.ticket.application.command.CreateTicketCommand;
import com.flowdesk.ticket.application.command.RejectCancelCommand;
import com.flowdesk.ticket.application.command.ReportUnresolvedCommand;
import com.flowdesk.ticket.application.command.RequestCancelCommand;
import com.flowdesk.ticket.application.command.RequestSupplementCommand;
import com.flowdesk.ticket.application.command.SubmitResolutionCommand;
import com.flowdesk.ticket.application.command.SupplementCommand;
import com.flowdesk.ticket.application.command.TransferCommand;
import com.flowdesk.ticket.application.command.WithdrawCancelRequestCommand;
import com.flowdesk.ticket.application.command.WithdrawSupplementRequestCommand;
import com.flowdesk.ticket.application.query.TicketRecordQuery;
import com.flowdesk.ticket.application.result.TicketActionResult;
import com.flowdesk.ticket.application.result.TicketAssigneeOptionResult;
import com.flowdesk.ticket.application.result.TicketCreatedResult;
import com.flowdesk.ticket.application.result.TicketRecordResult;
import com.flowdesk.ticket.application.service.TicketQueryService;
import com.flowdesk.ticket.application.service.TicketService;
import com.flowdesk.ticket.mapper.TicketMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 工单写动作服务的真实 MySQL 集成测试。
 *
 * <p>替换测不出来、只有真库才能证明的四类事实：</p>
 * <ol>
 *   <li><b>创建幂等</b>：同一 {@code (requester_id, submission_key)} 命中
 *       {@code uk_ticket_requester_submission}，并发下由唯一索引抛
 *       {@code DuplicateKeyException} 后走"新事务读回首次结果"的兜底路径；</li>
 *   <li><b>每日编号</b>：{@code ticket_daily_sequence} 的
 *       {@code INSERT ... ON DUPLICATE KEY UPDATE} + {@code FOR UPDATE} 在真并发下是否真的
 *       分配出连续不重复的编号；</li>
 *   <li><b>条件更新的真实影响行数</b>：{@code claimPending} / {@code advanceAssigneeAction} /
 *       {@code submitResolution} / {@code confirmResolution} 在版本过期时返回 0 行而不是抛异常，
 *       服务据此读回当前快照；</li>
 *   <li><b>CHECK 约束不被打穿</b>：{@code ck_ticket_status_deadline} /
 *       {@code ck_ticket_status_ended} / {@code ck_ticket_status_completion_method} /
 *       {@code ck_ticket_status_assignee} 在真实状态迁移后仍然成立。</li>
 * </ol>
 *
 * <p><b>「待补充」状态的进入方式（片 B 之后）</b>：{@code withdrawSupplementRequest} 与
 * {@code supplement} 都要求工单处于 {@code WAITING_FOR_REQUESTER}。片 A 期间没有任何接口能进入
 * 该状态，只能直接改库置位；片 B 的 {@code request-supplement} 落地后，{@link #insertWaitingForRequesterTicket}
 * 改为「建单 → 领取 → 请求补充」的真实链路，版本号、记录序号与期限都来自真实动作，
 * 造数只用于准备起点，不再伪造状态迁移的结果。</p>
 *
 * <p>与既有 IT 约定一致：{@code @ActiveProfiles("test")} + Testcontainers 临时库、
 * 不使用测试级 {@code @Transactional}（外层事务会掩盖 {@code REQUIRES_NEW} 与
 * {@code @Transactional} 的真实边界）、身份按 {@code JwtAuthenticationFilter} 的做法写入
 * {@code SecurityContextHolder}，权限由真实角色授权推导。竞态用例是真并发：
 * 每个线程先过一次 {@code CyclicBarrier}，再由用例断言"恰好一个赢家"。</p>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
class TicketServiceIT {

    private static final String MYSQL_IMAGE = "mysql:8.4.11";
    private static final String USERNAME_PREFIX = "itkt-";
    private static final String CATEGORY_PREFIX = "ITKT-";

    private static final String EMPLOYEE = "EMPLOYEE";
    private static final String IT_SUPPORT = "IT_SUPPORT";

    private static final String PENDING = "PENDING";
    private static final String PROCESSING = "PROCESSING";
    private static final String WAITING_FOR_REQUESTER = "WAITING_FOR_REQUESTER";
    private static final String WAITING_FOR_CONFIRMATION = "WAITING_FOR_CONFIRMATION";
    private static final String COMPLETED = "COMPLETED";
    private static final String CANCELED = "CANCELED";
    private static final String CLOSED = "CLOSED";

    /** 与 {@code application.yml} 的 {@code flowdesk.ticket.confirmation-window} 一致。 */
    private static final Duration CONFIRMATION_WINDOW = Duration.ofDays(7);

    /**
     * 与 {@code application.yml} 的 {@code flowdesk.ticket.supplement-window} 一致。
     *
     * <p>片 B 落地后，「待补充」不再靠直接改库造数，而是由 {@code request-supplement} 真实进入，
     * 因此这个窗口既用于断言服务端算出的期限，也用于断言它落库后的值。</p>
     */
    private static final Duration SUPPLEMENT_WINDOW = Duration.ofDays(7);

    /** 直接造数用的工单号，避开当日序号分配出来的编号空间。 */
    private static final AtomicInteger FIXTURE_SEQUENCE = new AtomicInteger(900);

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("flowdesk_ticket_it")
            .withUsername("flowdesk")
            .withPassword("flowdesk");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private TicketService ticketService;

    /** 时间线读取与"无权查看统一 404"要和写动作使用同一份可见性 SQL。 */
    @Autowired
    private TicketQueryService ticketQueryService;

    /** 条件更新的真实影响行数直接对 Mapper 断言，不经过服务的前置校验。 */
    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private JdbcTemplate jdbc;

    /** Redis 不属于本切片，用替身满足认证模块依赖（adapter 仍是真的）。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    private long employeeId;
    private long itUserId;
    private long otherItId;
    private long categoryId;

    @BeforeEach
    void prepareFixtures() {
        employeeId = insertUser("employee", EMPLOYEE);
        itUserId = insertUser("it", IT_SUPPORT);
        otherItId = insertUser("it-other", IT_SUPPORT);
        categoryId = insertCategory("主分类");
    }

    @AfterEach
    void removeIntegrationTestData() {
        SecurityContextHolder.clearContext();
        jdbc.update("""
                DELETE participant
                FROM ticket_participant participant
                JOIN ticket ON ticket.id = participant.ticket_id
                JOIN ticket_category category ON category.id = ticket.category_id
                WHERE category.name LIKE ?
                """, CATEGORY_PREFIX + "%");
        jdbc.update("""
                DELETE record
                FROM ticket_record record
                JOIN ticket ON ticket.id = record.ticket_id
                JOIN ticket_category category ON category.id = ticket.category_id
                WHERE category.name LIKE ?
                """, CATEGORY_PREFIX + "%");
        // 片 D 起工单会有指向别的工单的关联（重复关闭），必须在删工单之前先按 source 清掉
        jdbc.update("""
                DELETE relation
                FROM ticket_relation relation
                JOIN ticket ON ticket.id = relation.source_ticket_id
                JOIN ticket_category category ON category.id = ticket.category_id
                WHERE category.name LIKE ?
                """, CATEGORY_PREFIX + "%");
        jdbc.update("""
                DELETE ticket
                FROM ticket
                JOIN ticket_category category ON category.id = ticket.category_id
                WHERE category.name LIKE ?
                """, CATEGORY_PREFIX + "%");
        jdbc.update("DELETE FROM ticket_category WHERE name LIKE ?", CATEGORY_PREFIX + "%");
        jdbc.update("""
                DELETE user_role
                FROM iam_user_role user_role
                JOIN iam_user user ON user.id = user_role.user_id
                WHERE user.username LIKE ?
                """, USERNAME_PREFIX + "%");
        jdbc.update("DELETE FROM iam_user WHERE username LIKE ?", USERNAME_PREFIX + "%");
    }

    // ---------- 创建幂等 ----------

    @Test
    void createsOnceForRepeatedSubmissionKeyAndReturnsTheFirstResult() {
        authenticateAs(employeeId, EMPLOYEE);
        String submissionKey = UUID.randomUUID().toString();

        TicketCreatedResult first = createTicket(submissionKey, "幂等创建");
        TicketCreatedResult repeated = createTicket(submissionKey, "幂等创建-重复提交");

        assertThat(repeated.ticketNo()).as("同一提交键必须返回同一编号").isEqualTo(first.ticketNo());
        assertThat(first.status()).isEqualTo(PENDING);
        assertThat(first.version()).isZero();
        assertThat(first.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(first.ticketNo()).matches("FD-\\d{8}-\\d{3,}");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket
                WHERE requester_id = ? AND submission_key = ?
                """, Integer.class, employeeId, submissionKey)).as("库里只能有一行").isEqualTo(1);
        assertThat(countRecords(ticketIdOf(first.ticketNo()), "CREATE"))
                .as("重复提交不能再写一条创建记录")
                .isEqualTo(1);
    }

    /**
     * 真并发：两个线程用同一提交键同时创建。
     *
     * <p>两边的事务都会先做"查是否已存在"的预检查，且必然同时通过；胜者插入成功，
     * 败者的插入会被唯一索引拒绝并抛 {@code DuplicateKeyException}。这条用例证明兜底路径
     * 在真库上确实能让两个调用拿到同一编号，而不是把第二个调用变成错误。</p>
     */
    @Test
    void concurrentCreatesWithSameSubmissionKeyReturnTheSameTicketNumber() throws Exception {
        String submissionKey = UUID.randomUUID().toString();
        long sequenceBefore = dailySequenceTotal();

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    authenticateAs(employeeId, EMPLOYEE);
                    return createTicket(submissionKey, "并发幂等甲");
                },
                () -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    authenticateAs(employeeId, EMPLOYEE);
                    return createTicket(submissionKey, "并发幂等乙");
                }));

        assertThat(results).as("两个调用都必须成功返回，而不是一个成功一个异常")
                .allSatisfy(result -> assertThat(result).isInstanceOf(TicketCreatedResult.class));
        List<String> ticketNos = results.stream()
                .map(TicketCreatedResult.class::cast)
                .map(TicketCreatedResult::ticketNo)
                .distinct()
                .toList();
        assertThat(ticketNos).as("两个调用返回同一编号").hasSize(1);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket
                WHERE requester_id = ? AND submission_key = ?
                """, Integer.class, employeeId, submissionKey)).isEqualTo(1);
        assertThat(dailySequenceTotal())
                .as("败者事务整体回滚，当日编号只被消耗一次")
                .isEqualTo(sequenceBefore + 1);
    }

    // ---------- 每日编号 ----------

    /**
     * 真并发：6 个线程在同一业务日各用不同提交键创建。
     *
     * <p>序号分配是 {@code INSERT ... ON DUPLICATE KEY UPDATE} 加 {@code SELECT ... FOR UPDATE}，
     * 行锁持续到各自事务结束，因此并发下必须得到 6 个连续的编号，且
     * {@code ticket_daily_sequence.current_value} 与最大序号一致。</p>
     */
    @Test
    void concurrentCreatesOnOneBusinessDateAllocateConsecutiveDistinctNumbers() throws Exception {
        int threads = 6;
        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            int threadIndex = index;
            tasks.add(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                authenticateAs(employeeId, EMPLOYEE);
                return createTicket(UUID.randomUUID().toString(), "每日编号-" + threadIndex);
            });
        }

        List<TicketCreatedResult> created = runConcurrently(tasks).stream()
                .map(TicketCreatedResult.class::cast)
                .toList();

        assertThat(created).hasSize(threads);
        List<String> ticketNos = created.stream().map(TicketCreatedResult::ticketNo).toList();
        assertThat(ticketNos).doesNotHaveDuplicates();

        Set<String> datePrefixes = ticketNos.stream()
                .map(ticketNo -> ticketNo.substring(0, ticketNo.lastIndexOf('-')))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(datePrefixes).as("同一业务日的编号前缀一致").hasSize(1);

        List<Integer> sequenceNumbers = ticketNos.stream()
                .map(ticketNo -> Integer.valueOf(ticketNo.substring(ticketNo.lastIndexOf('-') + 1)))
                .sorted()
                .toList();
        assertThat(sequenceNumbers).doesNotHaveDuplicates();
        assertThat(sequenceNumbers.getLast() - sequenceNumbers.getFirst() + 1)
                .as("序号连续无跳号")
                .isEqualTo(threads);

        String businessDate = datePrefixes.iterator().next().substring("FD-".length());
        LocalDate date = LocalDate.parse(businessDate, DateTimeFormatter.BASIC_ISO_DATE);
        assertThat(currentSequence(date))
                .as("当日序号水位线与实际分配出的最大序号一致")
                .isEqualTo(sequenceNumbers.getLast());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket WHERE ticket_no IN ("
                        + ticketNos.stream().map(no -> "?").collect(Collectors.joining(", "))
                        + ")", Integer.class, ticketNos.toArray()))
                .as("每个编号都真实落库")
                .isEqualTo(threads);
    }

    // ---------- 领取并发 ----------

    /**
     * 真并发：两名 IT 同时领取同一张 {@code PENDING} 工单。
     *
     * <p>唯一胜者判定在 {@code claimPending} 的 {@code WHERE} 里（版本、状态、负责人、非提交人），
     * 因此必须恰好一个成功；败者的 {@code 409} 要带上库里最新的版本与状态，
     * 终态只能有一个负责人、一条参与历史、一条领取记录。</p>
     */
    @Test
    void concurrentClaimOfSamePendingTicketHasExactlyOneWinner() throws Exception {
        long ticketId = insertPendingTicket(employeeId);
        String ticketNo = ticketNoOf(ticketId);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> claimAfterBarrier(barrier, itUserId, ticketNo),
                () -> claimAfterBarrier(barrier, otherItId, ticketNo)));

        List<TicketActionResult> winners = results.stream()
                .filter(TicketActionResult.class::isInstance)
                .map(TicketActionResult.class::cast)
                .toList();
        assertThat(winners).as("两个 IT 并发领取同一张工单只能有一个成功").hasSize(1);
        assertThat(winners.getFirst().status()).isEqualTo(PROCESSING);
        assertThat(winners.getFirst().version()).isEqualTo(1L);
        assertThat(winners.getFirst().actionDeadlineAt()).isNull();

        ApiException loser = singleApiException(results);
        assertThat(loser.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(loser.code()).isEqualTo("TICKET_CONFLICT");
        assertThat(loser.resourceVersion()).as("冲突响应携带库里最新的版本")
                .isEqualTo(versionOf(ticketId));
        assertThat(loser.resourceStatus()).as("冲突响应携带库里最新的状态")
                .isEqualTo(statusOf(ticketId));

        assertThat(statusOf(ticketId)).isEqualTo(PROCESSING);
        assertThat(versionOf(ticketId)).isEqualTo(1);
        assertThat(assigneeOf(ticketId)).isEqualTo(winners.getFirst().assignee().id());
        assertThat(countParticipants(ticketId)).as("只有胜者写入参与历史").isEqualTo(1);
        assertThat(participantUserId(ticketId)).isEqualTo(winners.getFirst().assignee().id());
        assertThat(countRecords(ticketId, "CLAIM")).as("只有胜者写入领取记录").isEqualTo(1);
        assertThat(recordSequenceOf(ticketId, "CLAIM")).isEqualTo(2);
    }

    // ---------- 条件更新的真实影响行数 ----------

    /**
     * 四个条件更新在版本过期时返回 0 行（不是抛异常），并且一个字节都不改；
     * 版本与状态都对时同样必须真实影响 1 行 —— 否则"0 行"无法说明条件生效。
     */
    @Test
    void conditionalUpdatesReportRealAffectedRowsInsteadOfThrowing() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        LocalDateTime deadline = now.plus(CONFIRMATION_WINDOW);
        long staleVersion = 99L;

        long processingId = insertProcessingTicket(employeeId, itUserId);
        long versionBefore = versionOf(processingId);
        int recordSeqBefore = recordSeqOf(processingId);

        assertThat(ticketMapper.claimPending(processingId, staleVersion, itUserId, now))
                .as("领取：版本过期返回 0 行").isZero();
        assertThat(ticketMapper.advanceAssigneeAction(processingId, staleVersion, itUserId, now))
                .as("追加处理记录：版本过期返回 0 行").isZero();
        assertThat(ticketMapper.submitResolution(
                processingId, staleVersion, itUserId, deadline, now))
                .as("提交解决结果：版本过期返回 0 行").isZero();
        assertThat(ticketMapper.confirmResolution(processingId, staleVersion, employeeId, now))
                .as("确认解决结果：版本过期返回 0 行").isZero();

        assertThat(statusOf(processingId)).isEqualTo(PROCESSING);
        assertThat(versionOf(processingId)).isEqualTo(versionBefore);
        assertThat(recordSeqOf(processingId)).isEqualTo(recordSeqBefore);
        assertThat(actionDeadlineOf(processingId)).isNull();
        assertThat(endedAtOf(processingId)).isNull();
        assertThat(completionMethodOf(processingId)).isNull();

        // 赢的路径：影响行数必须是 1，且状态机四个动作依次推进后仍满足全部 CHECK 约束
        long pendingId = insertPendingTicket(employeeId);
        assertThat(ticketMapper.claimPending(pendingId, versionOf(pendingId), itUserId, now))
                .as("领取：唯一满足条件的行").isEqualTo(1);
        assertThat(ticketMapper.claimPending(pendingId, versionOf(pendingId), otherItId, now))
                .as("已经不是 PENDING：再领取返回 0 行").isZero();
        assertThat(ticketMapper.advanceAssigneeAction(pendingId, versionOf(pendingId), itUserId, now))
                .isEqualTo(1);
        assertThat(ticketMapper.submitResolution(
                pendingId, versionOf(pendingId), itUserId, deadline, now)).isEqualTo(1);
        assertThat(ticketMapper.confirmResolution(
                pendingId, versionOf(pendingId), employeeId, now)).isEqualTo(1);

        assertThat(statusOf(pendingId)).isEqualTo(COMPLETED);
        assertThat(actionDeadlineOf(pendingId)).isNull();
        assertThat(endedAtOf(pendingId)).isNotNull();
        assertThat(completionMethodOf(pendingId)).isEqualTo("REQUESTER_CONFIRMED");
        assertThat(assigneeOf(pendingId)).isEqualTo(itUserId);
    }

    /**
     * 服务层的"版本过期"是 409 而不是 500，并且异常里带回的快照等于库里的最新值。
     *
     * <p>版本过期会在服务的前置校验里被拦下（这一步读的就是库里的当前行），异常携带的
     * {@code resourceVersion}/{@code resourceStatus} 因此必须与直查结果逐字一致。
     * "条件更新真的返回 0 行"那条路径由本类的并发用例与上一个用例分别证明。</p>
     */
    @Test
    void staleVersionConflictsCarryTheCurrentDatabaseSnapshot() {
        long processingId = insertProcessingTicket(employeeId, itUserId);
        String processingNo = ticketNoOf(processingId);
        long waitingId = insertWaitingForConfirmationTicket(employeeId, itUserId);
        String waitingNo = ticketNoOf(waitingId);
        long pendingId = insertPendingTicket(employeeId);
        String pendingNo = ticketNoOf(pendingId);
        long staleVersion = 99L;

        authenticateAs(itUserId, IT_SUPPORT);
        assertStaleConflict(
                () -> ticketService.claim(pendingNo, new ClaimTicketCommand(staleVersion)),
                pendingId);
        assertStaleConflict(
                () -> ticketService.addProcessingRecord(processingNo,
                        new AddProcessingRecordCommand(staleVersion, "过期版本")),
                processingId);
        assertStaleConflict(
                () -> ticketService.submitResolution(processingNo,
                        new SubmitResolutionCommand(staleVersion, "过期版本")),
                processingId);

        authenticateAs(employeeId, EMPLOYEE);
        assertStaleConflict(
                () -> ticketService.confirmResolution(waitingNo,
                        new ConfirmResolutionCommand(staleVersion)),
                waitingId);

        assertThat(statusOf(processingId)).isEqualTo(PROCESSING);
        assertThat(versionOf(processingId)).as("造数工单的 version 与 record_seq 自洽").isZero();
        assertThat(recordSeqOf(processingId)).isEqualTo(1);
        assertThat(statusOf(waitingId)).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(versionOf(waitingId)).isZero();
        assertThat(statusOf(pendingId)).isEqualTo(PENDING);
        assertThat(versionOf(pendingId)).isZero();
        assertThat(countRecords(processingId, "PROCESS")).isZero();
        assertThat(countRecords(processingId, "RESOLUTION")).isZero();
        assertThat(countRecords(waitingId, "COMPLETION")).isZero();
    }

    // ---------- 同版本并发写入 ----------

    /**
     * 真并发：两个线程用同一版本追加处理记录。
     *
     * <p>两边都通过前置校验后同时执行 {@code advanceAssigneeAction}，条件更新只有一个影响 1 行，
     * 败者读到的是已经 +1 的版本。终态版本只 +1、{@code PROCESS} 记录只多 1 条。</p>
     */
    @Test
    void concurrentProcessingAppendWithSameVersionHasExactlyOneWinner() throws Exception {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> processAfterBarrier(barrier, ticketNo, versionBefore, "  第一条处理记录  "),
                () -> processAfterBarrier(barrier, ticketNo, versionBefore, "第二条处理记录")));

        List<TicketActionResult> winners = results.stream()
                .filter(TicketActionResult.class::isInstance)
                .map(TicketActionResult.class::cast)
                .toList();
        assertThat(winners).as("同版本并发追加只能有一个成功").hasSize(1);
        assertThat(winners.getFirst().status()).isEqualTo(PROCESSING);
        assertThat(winners.getFirst().version()).isEqualTo(versionBefore + 1L);

        ApiException loser = singleApiException(results);
        assertThat(loser.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(loser.code()).isEqualTo("TICKET_CONFLICT");
        assertThat(loser.resourceVersion()).isEqualTo(versionOf(ticketId));
        assertThat(loser.resourceStatus()).isEqualTo(PROCESSING);

        assertThat(versionOf(ticketId)).as("终态版本只 +1").isEqualTo(versionBefore + 1);
        assertThat(recordSeqOf(ticketId)).isEqualTo(2);
        assertThat(countRecords(ticketId, "PROCESS")).as("只多一条处理记录").isEqualTo(1);
        assertThat(recordContent(ticketId, "PROCESS"))
                .as("落库正文是裁剪后的原文，且只能是其中一个赢家写的内容")
                .isIn("第一条处理记录", "第二条处理记录");
    }

    /** 真并发：两个线程用同一版本提交解决结果，只能有一个进入待确认。 */
    @Test
    void concurrentResolutionSubmitWithSameVersionHasExactlyOneWinner() throws Exception {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> resolveAfterBarrier(barrier, ticketNo, versionBefore, "第一条解决结论"),
                () -> resolveAfterBarrier(barrier, ticketNo, versionBefore, "第二条解决结论")));

        List<TicketActionResult> winners = results.stream()
                .filter(TicketActionResult.class::isInstance)
                .map(TicketActionResult.class::cast)
                .toList();
        assertThat(winners).hasSize(1);
        assertThat(winners.getFirst().status()).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(winners.getFirst().actionDeadlineAt()).isNotNull();

        ApiException loser = singleApiException(results);
        assertThat(loser.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(loser.resourceVersion()).isEqualTo(versionOf(ticketId));
        assertThat(loser.resourceStatus()).isEqualTo(WAITING_FOR_CONFIRMATION);

        assertThat(versionOf(ticketId)).isEqualTo(versionBefore + 1);
        assertThat(statusOf(ticketId)).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(countRecords(ticketId, "RESOLUTION")).isEqualTo(1);
        assertThat(actionDeadlineOf(ticketId))
                .as("ck_ticket_status_deadline：进入待确认必须写入截止时间")
                .isNotNull();
        assertThat(endedAtOf(ticketId)).isNull();
        assertThat(completionMethodOf(ticketId)).isNull();
    }

    /** 真并发：提交人两个线程用同一版本确认，只能有一个进入终态。 */
    @Test
    void concurrentConfirmationWithSameVersionHasExactlyOneWinner() throws Exception {
        long ticketId = insertWaitingForConfirmationTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> confirmAfterBarrier(barrier, ticketNo, versionBefore),
                () -> confirmAfterBarrier(barrier, ticketNo, versionBefore)));

        List<TicketActionResult> winners = results.stream()
                .filter(TicketActionResult.class::isInstance)
                .map(TicketActionResult.class::cast)
                .toList();
        assertThat(winners).hasSize(1);
        assertThat(winners.getFirst().status()).isEqualTo(COMPLETED);

        ApiException loser = singleApiException(results);
        assertThat(loser.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(loser.resourceVersion()).isEqualTo(versionOf(ticketId));
        assertThat(loser.resourceStatus()).isEqualTo(COMPLETED);

        assertThat(versionOf(ticketId)).isEqualTo(versionBefore + 1);
        assertThat(countRecords(ticketId, "COMPLETION")).isEqualTo(1);
        assertThat(actionDeadlineOf(ticketId))
                .as("ck_ticket_status_deadline：终态不得再有截止时间")
                .isNull();
        assertThat(endedAtOf(ticketId))
                .as("ck_ticket_status_ended：终态必须有结束时间")
                .isNotNull();
        assertThat(completionMethodOf(ticketId)).isEqualTo("REQUESTER_CONFIRMED");
    }

    // ---------- 全流程与 CHECK 约束 ----------

    @Test
    void fullLifecycleKeepsCheckConstraintsSatisfied() {
        authenticateAs(employeeId, EMPLOYEE);
        TicketCreatedResult created = createTicket(UUID.randomUUID().toString(), "全流程工单");
        long ticketId = ticketIdOf(created.ticketNo());

        assertThat(statusOf(ticketId)).isEqualTo(PENDING);
        assertThat(versionOf(ticketId)).isZero();
        assertThat(recordSeqOf(ticketId)).isEqualTo(1);

        authenticateAs(itUserId, IT_SUPPORT);
        TicketActionResult claimed = ticketService.claim(
                created.ticketNo(), new ClaimTicketCommand(created.version()));
        assertThat(claimed.status()).isEqualTo(PROCESSING);
        assertThat(claimed.version()).isEqualTo(1L);
        assertThat(claimed.assignee().id()).isEqualTo(itUserId);
        assertThat(actionDeadlineOf(ticketId)).isNull();

        TicketActionResult processed = ticketService.addProcessingRecord(
                created.ticketNo(), new AddProcessingRecordCommand(claimed.version(), "已联系厂商"));
        assertThat(processed.status()).isEqualTo(PROCESSING);
        assertThat(processed.version()).isEqualTo(2L);

        TicketActionResult resolved = ticketService.submitResolution(
                created.ticketNo(), new SubmitResolutionCommand(processed.version(), "已更换网线"));
        assertThat(resolved.status()).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(resolved.version()).isEqualTo(3L);
        assertThat(resolved.actionDeadlineAt()).isNotNull();
        assertThat(Duration.between(resolved.actionTime(), resolved.actionDeadlineAt()))
                .as("确认期限由服务端按配置计算，不接受客户端传入")
                .isEqualTo(CONFIRMATION_WINDOW);
        assertThat(actionDeadlineOf(ticketId))
                .as("ck_ticket_status_deadline：待确认必须有截止时间")
                .isEqualTo(resolved.actionDeadlineAt().toLocalDateTime());
        assertThat(endedAtOf(ticketId))
                .as("ck_ticket_status_ended：非终态不得有结束时间")
                .isNull();
        assertThat(completionMethodOf(ticketId))
                .as("ck_ticket_status_completion_method：非已完成不得有完成方式")
                .isNull();

        authenticateAs(employeeId, EMPLOYEE);
        TicketActionResult completed = ticketService.confirmResolution(
                created.ticketNo(), new ConfirmResolutionCommand(resolved.version()));
        assertThat(completed.status()).isEqualTo(COMPLETED);
        assertThat(completed.version()).isEqualTo(4L);
        assertThat(completed.actionDeadlineAt()).isNull();
        assertThat(completed.assignee().id()).as("完成后仍返回最后负责人摘要").isEqualTo(itUserId);

        assertThat(actionDeadlineOf(ticketId))
                .as("ck_ticket_status_deadline：终态期限必须清空")
                .isNull();
        assertThat(endedAtOf(ticketId))
                .as("ck_ticket_status_ended：终态必须有结束时间")
                .isNotNull();
        assertThat(completionMethodOf(ticketId)).isEqualTo("REQUESTER_CONFIRMED");
        assertThat(assigneeOf(ticketId))
                .as("ck_ticket_status_assignee：已完成保留最后负责人")
                .isEqualTo(itUserId);
        assertThat(recordSeqOf(ticketId)).isEqualTo(5);
    }

    @Test
    void timelineIsSequentialWithoutGapsAndStoresTrimmedContent() {
        authenticateAs(employeeId, EMPLOYEE);
        TicketCreatedResult created = createTicket(
                UUID.randomUUID().toString(), "  时间线标题  ");
        long ticketId = ticketIdOf(created.ticketNo());

        authenticateAs(itUserId, IT_SUPPORT);
        TicketActionResult claimed = ticketService.claim(
                created.ticketNo(), new ClaimTicketCommand(created.version()));
        TicketActionResult first = ticketService.addProcessingRecord(created.ticketNo(),
                new AddProcessingRecordCommand(claimed.version(), "  第一条处理  "));
        TicketActionResult second = ticketService.addProcessingRecord(created.ticketNo(),
                new AddProcessingRecordCommand(first.version(), "\t第二条处理\n"));
        TicketActionResult resolved = ticketService.submitResolution(created.ticketNo(),
                new SubmitResolutionCommand(second.version(), "  已解决  "));

        authenticateAs(employeeId, EMPLOYEE);
        ticketService.confirmResolution(
                created.ticketNo(), new ConfirmResolutionCommand(resolved.version()));

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT sequence_no, record_type, content
                FROM ticket_record WHERE ticket_id = ? ORDER BY sequence_no ASC
                """, ticketId);
        // sequence_no 是 INT UNSIGNED：MySQL 驱动按无符号整型返回 Long，比较前先归一化
        assertThat(rows).extracting(row -> ((Number) row.get("sequence_no")).intValue())
                .as("序号从 1 起严格递增且无跳号")
                .containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(rows).extracting(row -> row.get("record_type"))
                .containsExactly("CREATE", "CLAIM", "PROCESS", "PROCESS", "RESOLUTION", "COMPLETION");
        assertThat(rows).extracting(row -> row.get("content"))
                .containsExactly(null, null, "第一条处理", "第二条处理", "已解决", null);

        assertThat(recordSeqOf(ticketId))
                .as("工单的 record_seq 与最大记录序号一致")
                .isEqualTo(6);
        assertThat(titleOf(ticketId)).as("标题首尾空白在落库前被裁剪").isEqualTo("时间线标题");

        // 读侧同样按 sequence_no 升序，正文取自裁剪后的库内值
        TicketRecordQuery query = new TicketRecordQuery();
        query.setPageSize(10);
        PageResult<TicketRecordResult> timeline = ticketQueryService.records(created.ticketNo(), query);

        assertThat(timeline.items()).extracting(TicketRecordResult::sequenceNo)
                .isSorted()
                .doesNotHaveDuplicates()
                .containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(timeline.items().get(2).context()).containsEntry("content", "第一条处理");
        assertThat(timeline.items().get(4).context()).containsEntry("content", "已解决");
    }

    // ---------- 片 A：退回处理中 ----------

    /**
     * 片 A 正常路径：员工反馈未解决后，工单从「待确认」回到「处理中」。
     *
     * <p>用真实服务把工单推到待确认（create → claim → submit-resolution），再执行反馈，
     * 因此版本号与记录序号都来自真实动作，而不是造数拼接。</p>
     */
    @Test
    void reportUnresolvedReturnsTicketToProcessingAndKeepsAssignee() {
        authenticateAs(employeeId, EMPLOYEE);
        TicketCreatedResult created = createTicket(UUID.randomUUID().toString(), "反馈未解决");
        long ticketId = ticketIdOf(created.ticketNo());

        authenticateAs(itUserId, IT_SUPPORT);
        TicketActionResult claimed = ticketService.claim(
                created.ticketNo(), new ClaimTicketCommand(created.version()));
        TicketActionResult resolved = ticketService.submitResolution(
                created.ticketNo(),
                new SubmitResolutionCommand(claimed.version(), "已更换网线"));

        authenticateAs(employeeId, EMPLOYEE);
        TicketActionResult reported = ticketService.reportUnresolved(
                created.ticketNo(),
                new ReportUnresolvedCommand(resolved.version(), "  复测后仍有故障  "));

        assertThat(reported.status()).isEqualTo(PROCESSING);
        assertThat(reported.version()).as("反馈只推进一个版本")
                .isEqualTo(resolved.version() + 1L);
        assertThat(reported.actionDeadlineAt()).as("退回处理中不再有期限").isNull();
        assertThat(reported.assignee().id()).as("退回不等于换人").isEqualTo(itUserId);

        assertThat(statusOf(ticketId)).isEqualTo(PROCESSING);
        assertThat(versionOf(ticketId)).as("库里版本只 +1")
                .isEqualTo(resolved.version() + 1L);
        assertThat(actionDeadlineOf(ticketId))
                .as("ck_ticket_status_deadline：离开待确认后期限必须为 NULL")
                .isNull();
        assertThat(endedAtOf(ticketId))
                .as("ck_ticket_status_ended：退回后不是终态").isNull();
        assertThat(completionMethodOf(ticketId))
                .as("ck_ticket_status_completion_method：退回后没有完成方式").isNull();
        assertThat(assigneeOf(ticketId)).as("负责人保留").isEqualTo(itUserId);
        assertThat(recordSeqOf(ticketId)).as("record_seq 与时间线同步推进").isEqualTo(4);

        List<Map<String, Object>> rows = timelineOf(ticketId);
        assertThat(rows).extracting(row -> ((Number) row.get("sequence_no")).intValue())
                .as("记录序号从 1 起连续无跳号")
                .containsExactly(1, 2, 3, 4);
        assertThat(rows).extracting(row -> row.get("record_type"))
                .as("解决结果作为历史保留，末尾追加未解决反馈")
                .containsExactly("CREATE", "CLAIM", "RESOLUTION", "UNSATISFIED_FEEDBACK");
        assertThat(lastRecordTypeOf(ticketId)).isEqualTo("UNSATISFIED_FEEDBACK");
        assertThat(recordReason(ticketId, "UNSATISFIED_FEEDBACK"))
                .as("反馈原因去除首尾空白后落库").isEqualTo("复测后仍有故障");
        assertThat(recordContent(ticketId, "UNSATISFIED_FEEDBACK"))
                .as("反馈记录没有正文").isNull();
        assertThat(recordContent(ticketId, "RESOLUTION"))
                .as("历史解决结果不被改写").isEqualTo("已更换网线");
    }

    /**
     * 片 A 正常路径：当前负责人撤回补充请求后，工单从「待补充」回到「处理中」。
     *
     * <p>「待补充」由 {@link #insertWaitingForRequesterTicket} 经真实链路进入
     * （建单 → 领取 → 请求补充），因此时间线前两条就是 {@code CREATE} 与 {@code CLAIM}，
     * 第三条是片 B 的补充请求记录。</p>
     */
    @Test
    void withdrawSupplementRequestReturnsTicketToProcessingAndClearsSupplementDeadline() {
        long ticketId = insertWaitingForRequesterTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);
        LocalDateTime supplementDeadline = actionDeadlineOf(ticketId);

        assertThat(statusOf(ticketId)).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(supplementDeadline)
                .as("待补充由 request-supplement 真实进入，因此必然有期限")
                .isNotNull();

        authenticateAs(itUserId, IT_SUPPORT);
        TicketActionResult withdrawn = ticketService.withdrawSupplementRequest(
                ticketNo,
                new WithdrawSupplementRequestCommand(versionBefore, "  信息已足够  "));

        assertThat(withdrawn.status()).isEqualTo(PROCESSING);
        assertThat(withdrawn.version()).isEqualTo(versionBefore + 1L);
        assertThat(withdrawn.actionDeadlineAt()).isNull();
        assertThat(withdrawn.assignee().id()).as("负责人不变").isEqualTo(itUserId);

        assertThat(statusOf(ticketId)).isEqualTo(PROCESSING);
        assertThat(versionOf(ticketId)).as("库里版本只 +1").isEqualTo(versionBefore + 1);
        assertThat(actionDeadlineOf(ticketId))
                .as("ck_ticket_status_deadline：离开待补充后期限必须为 NULL")
                .isNull();
        assertThat(endedAtOf(ticketId)).as("退回后不是终态").isNull();
        assertThat(assigneeOf(ticketId)).isEqualTo(itUserId);
        assertThat(recordSeqOf(ticketId)).isEqualTo(4);

        List<Map<String, Object>> rows = timelineOf(ticketId);
        assertThat(rows).extracting(row -> ((Number) row.get("sequence_no")).intValue())
                .as("补充请求与撤回记录接在同一条时间线上")
                .containsExactly(1, 2, 3, 4);
        assertThat(rows).extracting(row -> row.get("record_type"))
                .containsExactly("CREATE", "CLAIM", "SUPPLEMENT_REQUEST",
                        "SUPPLEMENT_REQUEST_WITHDRAWN");
        assertThat(lastRecordTypeOf(ticketId)).isEqualTo("SUPPLEMENT_REQUEST_WITHDRAWN");
        assertThat(recordReason(ticketId, "SUPPLEMENT_REQUEST_WITHDRAWN"))
                .as("撤回原因去除首尾空白后落库").isEqualTo("信息已足够");

        // 真库 CHECK 约束不是摆设：应用漏写期限时，非等待态带期限会被数据库直接拒绝，
        // 因此上面的 NULL 断言证明的是"约束被满足"，而不是"这一列恰好没人写"。
        // MySQL 对 CHECK 违反返回 error 3819 / SQL state HY000，Spring 只能归到
        // DataAccessException 的未分类子类，因此断言父类型 + 约束名。
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ticket SET action_deadline_at = ? WHERE id = ?",
                supplementDeadline, ticketId))
                .as("ck_ticket_status_deadline 在真库上确实生效")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_ticket_status_deadline");
    }

    /**
     * 真并发：两个线程用同一版本撤回补充请求。
     *
     * <p>两边都通过前置校验后同时执行条件更新，只有一个影响 1 行，败者读回已经 +1 的版本。
     * 终态版本只 +1、撤回记录只多 1 条、期限被清空。</p>
     */
    @Test
    void concurrentWithdrawSupplementRequestWithSameVersionHasExactlyOneWinner()
            throws Exception {
        long ticketId = insertWaitingForRequesterTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> withdrawAfterBarrier(barrier, ticketNo, versionBefore, "第一条撤回原因"),
                () -> withdrawAfterBarrier(barrier, ticketNo, versionBefore, "第二条撤回原因")));

        List<TicketActionResult> winners = results.stream()
                .filter(TicketActionResult.class::isInstance)
                .map(TicketActionResult.class::cast)
                .toList();
        assertThat(winners).as("同版本并发撤回只能有一个成功").hasSize(1);
        assertThat(winners.getFirst().status()).isEqualTo(PROCESSING);
        assertThat(winners.getFirst().version()).isEqualTo(versionBefore + 1L);
        assertThat(winners.getFirst().actionDeadlineAt()).isNull();

        ApiException loser = singleApiException(results);
        assertThat(loser.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(loser.code()).isEqualTo("TICKET_CONFLICT");
        assertThat(loser.resourceVersion()).as("冲突响应携带库里最新版本")
                .isEqualTo(versionOf(ticketId));
        assertThat(loser.resourceStatus()).isEqualTo(PROCESSING);

        assertThat(versionOf(ticketId)).as("终态版本只 +1").isEqualTo(versionBefore + 1);
        assertThat(countRecords(ticketId, "SUPPLEMENT_REQUEST_WITHDRAWN"))
                .as("只多一条撤回记录").isEqualTo(1);
        assertThat(recordSeqOf(ticketId)).isEqualTo(4);
        assertThat(actionDeadlineOf(ticketId))
                .as("ck_ticket_status_deadline：退回处理中后期限为 NULL").isNull();
        assertThat(recordReason(ticketId, "SUPPLEMENT_REQUEST_WITHDRAWN"))
                .as("落库原因只能来自其中一个赢家")
                .isIn("第一条撤回原因", "第二条撤回原因");
    }

    // ---------- 片 B：补充往返 ----------

    /**
     * 片 B 正常往返：请求补充 → 员工补充，工单回到「处理中」，负责人不变、期限清空。
     *
     * <p>这条用例把两段独立的事实串起来：{@code request-supplement} 写入的期限与服务端算出的
     * 窗口一致，{@code supplement} 之后期限被清空（{@code ck_ticket_status_deadline} 要求
     * 非等待态必须为空）。补充不等于换人，因此负责人一路保持。</p>
     */
    @Test
    void supplementRoundTripReturnsTicketToProcessingAndKeepsAssignee() {
        authenticateAs(employeeId, EMPLOYEE);
        TicketCreatedResult created = createTicket(UUID.randomUUID().toString(), "补充往返");
        long ticketId = ticketIdOf(created.ticketNo());

        authenticateAs(itUserId, IT_SUPPORT);
        TicketActionResult claimed = ticketService.claim(
                created.ticketNo(), new ClaimTicketCommand(created.version()));
        TicketActionResult requested = ticketService.requestSupplement(
                created.ticketNo(),
                new RequestSupplementCommand(claimed.version(), "  请补充打印机型号  "));

        assertThat(requested.status()).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(requested.version()).as("请求补充只推进一个版本")
                .isEqualTo(claimed.version() + 1L);
        assertThat(requested.actionDeadlineAt()).as("期限由服务端计算").isNotNull();
        assertThat(Duration.between(requested.actionTime(), requested.actionDeadlineAt()))
                .as("补充期限来自 flowdesk.ticket.supplement-window，不接受客户端传入")
                .isEqualTo(SUPPLEMENT_WINDOW);
        assertThat(actionDeadlineOf(ticketId))
                .as("ck_ticket_status_deadline：待补充必须有期限，且与响应一致")
                .isEqualTo(requested.actionDeadlineAt().toLocalDateTime());
        assertThat(statusOf(ticketId)).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(assigneeOf(ticketId)).as("请求补充不换人").isEqualTo(itUserId);
        assertThat(recordSeqOf(ticketId)).isEqualTo(3);

        authenticateAs(employeeId, EMPLOYEE);
        TicketActionResult supplemented = ticketService.supplement(
                created.ticketNo(),
                new SupplementCommand(requested.version(), "  型号是 L3153，报错见附件说明  "));

        assertThat(supplemented.status()).isEqualTo(PROCESSING);
        assertThat(supplemented.version()).isEqualTo(requested.version() + 1L);
        assertThat(supplemented.actionDeadlineAt()).as("回到处理中不再有期限").isNull();
        assertThat(supplemented.assignee().id()).as("原负责人继续处理").isEqualTo(itUserId);

        assertThat(statusOf(ticketId)).isEqualTo(PROCESSING);
        assertThat(actionDeadlineOf(ticketId))
                .as("ck_ticket_status_deadline：离开待补充后期限必须为 NULL")
                .isNull();
        assertThat(endedAtOf(ticketId)).as("补充后不是终态").isNull();
        assertThat(assigneeOf(ticketId)).isEqualTo(itUserId);
        assertThat(recordSeqOf(ticketId)).isEqualTo(4);

        List<Map<String, Object>> rows = timelineOf(ticketId);
        assertThat(rows).extracting(row -> ((Number) row.get("sequence_no")).intValue())
                .as("记录序号从 1 起连续无跳号")
                .containsExactly(1, 2, 3, 4);
        assertThat(rows).extracting(row -> row.get("record_type"))
                .containsExactly("CREATE", "CLAIM", "SUPPLEMENT_REQUEST", "REQUESTER_SUPPLEMENT");
        assertThat(recordContent(ticketId, "SUPPLEMENT_REQUEST"))
                .as("请求内容去除首尾空白后落库").isEqualTo("请补充打印机型号");
        assertThat(recordContent(ticketId, "REQUESTER_SUPPLEMENT"))
                .as("补充正文去除首尾空白后落库").isEqualTo("型号是 L3153，报错见附件说明");
        assertThat(recordDeadlineOf(ticketId, "SUPPLEMENT_REQUEST"))
                .as("请求记录同时冻结当时的补充期限")
                .isEqualTo(requested.actionDeadlineAt().toLocalDateTime());
        assertThat(recordDeadlineOf(ticketId, "REQUESTER_SUPPLEMENT"))
                .as("补充记录不写期限：期限已经失效").isNull();

        // 补充完成后负责人可以继续推进，往返是闭环而不是死胡同
        authenticateAs(itUserId, IT_SUPPORT);
        TicketActionResult processed = ticketService.addProcessingRecord(
                created.ticketNo(),
                new AddProcessingRecordCommand(supplemented.version(), "已按补充信息定位到驱动问题"));
        assertThat(processed.status()).isEqualTo(PROCESSING);
        assertThat(processed.version()).isEqualTo(supplemented.version() + 1L);
    }

    /**
     * 真并发：两个线程用同一版本请求补充。
     *
     * <p>两边都通过前置校验后同时执行条件更新，只有一个影响 1 行，败者读回已经 +1 的版本。
     * 终态版本只 +1、补充请求记录只多 1 条、期限只写一次。</p>
     */
    @Test
    void concurrentRequestSupplementWithSameVersionHasExactlyOneWinner() throws Exception {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> requestSupplementAfterBarrier(barrier, ticketNo, versionBefore, "第一次请求补充"),
                () -> requestSupplementAfterBarrier(barrier, ticketNo, versionBefore, "第二次请求补充")));

        List<TicketActionResult> winners = results.stream()
                .filter(TicketActionResult.class::isInstance)
                .map(TicketActionResult.class::cast)
                .toList();
        assertThat(winners).as("同版本并发请求补充只能有一个成功").hasSize(1);
        assertThat(winners.getFirst().status()).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(winners.getFirst().version()).isEqualTo(versionBefore + 1L);
        assertThat(winners.getFirst().actionDeadlineAt()).isEqualTo(
                winners.getFirst().actionTime().plus(SUPPLEMENT_WINDOW));

        ApiException loser = singleApiException(results);
        assertThat(loser.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(loser.code()).isEqualTo("TICKET_CONFLICT");
        assertThat(loser.resourceVersion()).as("冲突响应携带库里最新版本")
                .isEqualTo(versionOf(ticketId));
        assertThat(loser.resourceStatus()).isEqualTo(WAITING_FOR_REQUESTER);

        assertThat(versionOf(ticketId)).as("终态版本只 +1").isEqualTo(versionBefore + 1);
        assertThat(countRecords(ticketId, "SUPPLEMENT_REQUEST"))
                .as("只多一条补充请求记录").isEqualTo(1);
        assertThat(recordSeqOf(ticketId))
                .as("造数直接给出「处理中」，因此这里是 CREATE + 一次成功的补充请求")
                .isEqualTo(2);
        assertThat(statusOf(ticketId)).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(actionDeadlineOf(ticketId))
                .as("期限只能由赢家写入一次").isNotNull();
        assertThat(recordContent(ticketId, "SUPPLEMENT_REQUEST"))
                .as("落库内容只能来自其中一个赢家")
                .isIn("第一次请求补充", "第二次请求补充");
    }

    /**
     * 跨动作互斥：同一张「处理中」工单上并发执行 {@code request-supplement} 与
     * {@code submit-resolution}。
     *
     * <p>两个动作都要求「处理中 + 本人是负责人」，条件更新的预期状态也相同，因此只可能有一个
     * 影响 1 行。终态必须与落库的那条记录自洽：补充请求 → {@code WAITING_FOR_REQUESTER} +
     * {@code SUPPLEMENT_REQUEST}；提交解决 → {@code WAITING_FOR_CONFIRMATION} + {@code RESOLUTION}。
     * 这也是"待补充期间不能直接提交解决结果"在并发下的表现。</p>
     */
    @Test
    void concurrentSupplementRequestAndResolutionSubmitHaveExactlyOneWinner() throws Exception {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> requestSupplementAfterBarrier(barrier, ticketNo, versionBefore, "并发请求补充"),
                () -> resolveAfterBarrier(barrier, ticketNo, versionBefore, "并发提交解决")));

        List<TicketActionResult> winners = results.stream()
                .filter(TicketActionResult.class::isInstance)
                .map(TicketActionResult.class::cast)
                .toList();
        assertThat(winners).as("跨动作并发只能有一个成功").hasSize(1);
        singleApiException(results);

        String finalStatus = statusOf(ticketId);
        assertThat(versionOf(ticketId)).as("跨动作并发后版本只 +1").isEqualTo(versionBefore + 1);

        if (WAITING_FOR_REQUESTER.equals(finalStatus)) {
            assertThat(countRecords(ticketId, "SUPPLEMENT_REQUEST"))
                    .as("赢的是请求补充").isEqualTo(1);
            assertThat(countRecords(ticketId, "RESOLUTION")).isZero();
            assertThat(actionDeadlineOf(ticketId))
                    .as("ck_ticket_status_deadline：待补充必须有期限").isNotNull();
        } else {
            assertThat(finalStatus).as("赢的只能是提交解决").isEqualTo(WAITING_FOR_CONFIRMATION);
            assertThat(countRecords(ticketId, "RESOLUTION"))
                    .as("赢的是提交解决").isEqualTo(1);
            assertThat(countRecords(ticketId, "SUPPLEMENT_REQUEST")).isZero();
            assertThat(actionDeadlineOf(ticketId))
                    .as("ck_ticket_status_deadline：待确认必须有期限").isNotNull();
        }
    }

    /**
     * 跨动作互斥：同一张「待确认」工单上并发执行 {@code confirm-resolution} 与
     * {@code report-unresolved}。
     *
     * <p>两个动作的提交人相同、条件更新的预期状态都是 {@code WAITING_FOR_CONFIRMATION}，
     * 因此只可能有一个影响 1 行。终态必须与落库的那条记录自洽：确认 → {@code COMPLETED} +
     * {@code COMPLETION}；反馈 → {@code PROCESSING} + {@code UNSATISFIED_FEEDBACK}，
     * 两条记录不可能同时存在。</p>
     */
    @Test
    void concurrentConfirmationAndUnresolvedReportHaveExactlyOneWinner() throws Exception {
        long ticketId = insertWaitingForConfirmationTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> confirmAfterBarrier(barrier, ticketNo, versionBefore),
                () -> reportAfterBarrier(barrier, ticketNo, versionBefore, "并发反馈未解决")));

        List<TicketActionResult> winners = results.stream()
                .filter(TicketActionResult.class::isInstance)
                .map(TicketActionResult.class::cast)
                .toList();
        assertThat(winners).as("两个互斥动作只能有一个成功").hasSize(1);
        assertThat(winners.getFirst().version()).isEqualTo(versionBefore + 1L);

        ApiException loser = singleApiException(results);
        assertThat(loser.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(loser.code()).isEqualTo("TICKET_CONFLICT");
        assertThat(loser.resourceVersion()).as("冲突响应携带库里最新版本")
                .isEqualTo(versionOf(ticketId));
        assertThat(loser.resourceStatus()).as("冲突响应携带库里最新状态")
                .isEqualTo(statusOf(ticketId));

        assertThat(versionOf(ticketId)).as("终态版本只 +1").isEqualTo(versionBefore + 1);
        assertThat(recordSeqOf(ticketId)).isEqualTo(2);
        assertThat(countRecords(ticketId, "COMPLETION")
                + countRecords(ticketId, "UNSATISFIED_FEEDBACK"))
                .as("两个动作的记录不可能同时落库").isEqualTo(1);
        assertThat(assigneeOf(ticketId)).as("两个动作都不换负责人").isEqualTo(itUserId);

        if (COMPLETED.equals(winners.getFirst().status())) {
            assertThat(statusOf(ticketId)).isEqualTo(COMPLETED);
            assertThat(countRecords(ticketId, "UNSATISFIED_FEEDBACK")).isZero();
            assertThat(endedAtOf(ticketId))
                    .as("ck_ticket_status_ended：终态必须有结束时间").isNotNull();
            assertThat(completionMethodOf(ticketId)).isEqualTo("REQUESTER_CONFIRMED");
        } else {
            assertThat(winners.getFirst().status()).isEqualTo(PROCESSING);
            assertThat(statusOf(ticketId)).isEqualTo(PROCESSING);
            assertThat(countRecords(ticketId, "COMPLETION")).isZero();
            assertThat(endedAtOf(ticketId)).as("退回不是终态").isNull();
            assertThat(completionMethodOf(ticketId)).isNull();
            assertThat(recordReason(ticketId, "UNSATISFIED_FEEDBACK"))
                    .isEqualTo("并发反馈未解决");
        }

        assertThat(actionDeadlineOf(ticketId))
                .as("ck_ticket_status_deadline：无论谁赢，离开待确认后期限都为 NULL")
                .isNull();
    }

    // ---------- 权限与身份闸门 ----------

    /**
     * 闸门顺序：权限（403）先于可见性（404），可见性先于"是不是提交人"（403 自己提交的工单）。
     */
    @Test
    void claimPermissionGatePrecedesVisibilityAndSelfClaim() {
        long pendingId = insertPendingTicket(employeeId);
        String ticketNo = ticketNoOf(pendingId);

        authenticateAs(employeeId, EMPLOYEE);
        assertApiError(
                () -> ticketService.claim(ticketNo, new ClaimTicketCommand(0L)),
                HttpStatus.FORBIDDEN,
                "TICKET_ACTION_FORBIDDEN");
        // 权限在可见性之前：不存在的编号也先得到 403，不会泄露编号是否存在
        assertApiError(
                () -> ticketService.claim("FD-19700101-001", new ClaimTicketCommand(0L)),
                HttpStatus.FORBIDDEN,
                "TICKET_ACTION_FORBIDDEN");
        assertThat(statusOf(pendingId)).isEqualTo(PENDING);

        // 有领取权限且真的是 IT，但工单是自己提交的：仍然 403
        long ownTicketId = insertPendingTicket(itUserId);
        authenticateAs(itUserId, IT_SUPPORT);
        assertApiError(
                () -> ticketService.claim(ticketNoOf(ownTicketId), new ClaimTicketCommand(0L)),
                HttpStatus.FORBIDDEN,
                "TICKET_ACTION_FORBIDDEN");
        assertThat(statusOf(ownTicketId)).isEqualTo(PENDING);
    }

    /**
     * 会话里有 {@code TICKET_CLAIM} 但账号已经没有 {@code IT_SUPPORT} 角色：
     * 领取落库前必须再查一次真实角色，返回 403 而不是把工单发给一个已失去资格的账号。
     */
    @Test
    void claimWithoutItSupportRoleIsForbiddenEvenWhenSessionStillCarriesThePermission() {
        long noRoleUserId = insertUser("claim-no-role");
        long pendingId = insertPendingTicket(employeeId);

        authenticateWith(noRoleUserId, "itkt-claim-no-role",
                Set.of("TICKET_CLAIM", "TICKET_VIEW_QUEUE"));

        assertApiError(
                () -> ticketService.claim(ticketNoOf(pendingId), new ClaimTicketCommand(0L)),
                HttpStatus.FORBIDDEN,
                "TICKET_ACTION_FORBIDDEN");
        assertThat(statusOf(pendingId)).isEqualTo(PENDING);
        assertThat(countParticipants(pendingId)).isZero();
    }

    /** 能看见工单但不是当前负责人：按冲突返回，而不是 404 或 403。 */
    @Test
    void processingRecordByVisibleNonAssigneeIsConflict() {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        insertParticipant(ticketId, otherItId);

        authenticateAs(otherItId, IT_SUPPORT);

        ApiException conflict = catchApiError(() -> ticketService.addProcessingRecord(
                ticketNoOf(ticketId), new AddProcessingRecordCommand(versionOf(ticketId), "我不是负责人")));

        assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.code()).isEqualTo("TICKET_CONFLICT");
        assertThat(conflict.resourceStatus()).isEqualTo(PROCESSING);
        assertThat(conflict.resourceVersion()).isEqualTo(versionOf(ticketId));
        assertThat(countRecords(ticketId, "PROCESS")).isZero();
    }

    /**
     * 非提交人确认：调用方同时持有提交人动作权限（EMPLOYEE）与处理权限（IT_SUPPORT），
     * 而且在工单上可见，因此闸门必须落到"只有提交人能确认"这一条，返回 409。
     */
    @Test
    void confirmResolutionByVisibleNonRequesterIsConflict() {
        long dualRoleUserId = insertUser("dual-role", EMPLOYEE, IT_SUPPORT);
        long ticketId = insertWaitingForConfirmationTicket(employeeId, dualRoleUserId);

        authenticateAs(dualRoleUserId, EMPLOYEE, IT_SUPPORT);

        ApiException conflict = catchApiError(() -> ticketService.confirmResolution(
                ticketNoOf(ticketId), new ConfirmResolutionCommand(versionOf(ticketId))));

        assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.code()).isEqualTo("TICKET_CONFLICT");
        assertThat(conflict.resourceStatus()).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(statusOf(ticketId)).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(endedAtOf(ticketId)).isNull();
        assertThat(completionMethodOf(ticketId)).isNull();
        assertThat(countRecords(ticketId, "COMPLETION")).isZero();
    }

    /**
     * 片 A 权限闸门：IT 负责人缺 {@code TICKET_REQUESTER_ACTION}，即使他确实是这张「待确认」
     * 工单的负责人、也看得见工单，反馈未解决仍必须先被 403 拦下（权限先于身份）。
     */
    @Test
    void reportUnresolvedByItSupportIsForbiddenBeforeIdentityCheck() {
        long ticketId = insertWaitingForConfirmationTicket(employeeId, itUserId);

        authenticateAs(itUserId, IT_SUPPORT);

        assertApiError(
                () -> ticketService.reportUnresolved(ticketNoOf(ticketId),
                        new ReportUnresolvedCommand(versionOf(ticketId), "还没修好")),
                HttpStatus.FORBIDDEN,
                "TICKET_ACTION_FORBIDDEN");
        // 权限在可见性之前：不存在的编号也先得到 403，不会泄露编号是否存在
        assertApiError(
                () -> ticketService.reportUnresolved("FD-19700101-001",
                        new ReportUnresolvedCommand(0L, "还没修好")),
                HttpStatus.FORBIDDEN,
                "TICKET_ACTION_FORBIDDEN");

        assertThat(statusOf(ticketId)).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(actionDeadlineOf(ticketId)).as("未执行的反馈不能改动期限").isNotNull();
        assertThat(countRecords(ticketId, "UNSATISFIED_FEEDBACK")).isZero();
        assertThat(versionOf(ticketId)).isZero();
    }

    /**
     * 片 A 权限闸门：员工缺 {@code TICKET_PROCESS}，即使他是这张「待补充」工单的可见提交人，
     * 撤回请求也必须先被 403 拦下。
     */
    @Test
    void withdrawSupplementRequestByEmployeeIsForbiddenBeforeVisibilityCheck() {
        long ticketId = insertWaitingForRequesterTicket(employeeId, itUserId);

        authenticateAs(employeeId, EMPLOYEE);

        assertApiError(
                () -> ticketService.withdrawSupplementRequest(ticketNoOf(ticketId),
                        new WithdrawSupplementRequestCommand(versionOf(ticketId), "撤回")),
                HttpStatus.FORBIDDEN,
                "TICKET_ACTION_FORBIDDEN");
        assertApiError(
                () -> ticketService.withdrawSupplementRequest("FD-19700101-001",
                        new WithdrawSupplementRequestCommand(0L, "撤回")),
                HttpStatus.FORBIDDEN,
                "TICKET_ACTION_FORBIDDEN");

        assertThat(statusOf(ticketId)).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(actionDeadlineOf(ticketId)).as("未执行的撤回不能清空期限").isNotNull();
        assertThat(countRecords(ticketId, "SUPPLEMENT_REQUEST_WITHDRAWN")).isZero();
        assertThat(versionOf(ticketId))
                .as("领取 +1、请求补充 +1；两次被 403 拦下的撤回都没有推进版本")
                .isEqualTo(2);
    }

    /** 能看见工单但不是当前负责人：撤回按冲突返回，而不是 403 或 404。 */
    @Test
    void withdrawSupplementRequestByVisibleNonAssigneeIsConflict() {
        long ticketId = insertWaitingForRequesterTicket(employeeId, itUserId);
        insertParticipant(ticketId, otherItId);

        authenticateAs(otherItId, IT_SUPPORT);

        ApiException conflict = catchApiError(() -> ticketService.withdrawSupplementRequest(
                ticketNoOf(ticketId),
                new WithdrawSupplementRequestCommand(versionOf(ticketId), "我不是负责人")));

        assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.code()).isEqualTo("TICKET_CONFLICT");
        assertThat(conflict.resourceStatus()).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(conflict.resourceVersion()).isEqualTo(versionOf(ticketId));
        assertThat(statusOf(ticketId)).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(countRecords(ticketId, "SUPPLEMENT_REQUEST_WITHDRAWN")).isZero();
    }

    /**
     * 能看见工单但不是提交人：反馈未解决按冲突返回。
     * 该账号同时持有 {@code TICKET_REQUESTER_ACTION}（EMPLOYEE）与 {@code TICKET_PROCESS}
     * （IT_SUPPORT），因此闸门必须落到"只有提交人能反馈"这一条。
     */
    @Test
    void reportUnresolvedByVisibleNonRequesterIsConflict() {
        long dualRoleUserId = insertUser("dual-role-report", EMPLOYEE, IT_SUPPORT);
        long ticketId = insertWaitingForConfirmationTicket(employeeId, dualRoleUserId);

        authenticateAs(dualRoleUserId, EMPLOYEE, IT_SUPPORT);

        ApiException conflict = catchApiError(() -> ticketService.reportUnresolved(
                ticketNoOf(ticketId),
                new ReportUnresolvedCommand(versionOf(ticketId), "我不是提交人")));

        assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.code()).isEqualTo("TICKET_CONFLICT");
        assertThat(conflict.resourceStatus()).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(conflict.resourceVersion()).isEqualTo(versionOf(ticketId));
        assertThat(statusOf(ticketId)).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(actionDeadlineOf(ticketId)).isNotNull();
        assertThat(countRecords(ticketId, "UNSATISFIED_FEEDBACK")).isZero();
    }

    /** 无关用户：详情与动作都统一 404，不确认工单是否存在；有权限但看不见也一样。 */
    @Test
    void unrelatedUserCannotReadDetailOrClaim() {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);

        authenticateAs(otherItId, IT_SUPPORT);
        assertApiError(
                () -> ticketQueryService.detail(ticketNo),
                HttpStatus.NOT_FOUND,
                "TICKET_NOT_FOUND");
        assertApiError(
                () -> ticketService.claim(ticketNo, new ClaimTicketCommand(versionOf(ticketId))),
                HttpStatus.NOT_FOUND,
                "TICKET_NOT_FOUND");

        authenticateAs(employeeId, EMPLOYEE);
        assertApiError(
                () -> ticketQueryService.detail("FD-19700101-001"),
                HttpStatus.NOT_FOUND,
                "TICKET_NOT_FOUND");
    }

    // ---------- 片 C：调整与转交 ----------

    /**
     * 真实链路上的调整分类：建单 → 领取 → 调整。
     *
     * <p>库里分类被替换、状态与负责人不变、{@code version} 与 {@code record_seq} 各 +1，
     * 时间线多一条 {@code CATEGORY_CHANGE}（原分类 → 新分类 + 说明）——"调整"是留痕动作，
     * 不是把旧值抹掉。</p>
     */
    @Test
    void changeCategoryReplacesCategoryAndKeepsStatusAndAssignee() {
        long targetCategoryId = insertCategory("目标分类");
        authenticateAs(employeeId, EMPLOYEE);
        TicketCreatedResult created = createTicket(UUID.randomUUID().toString(), "调整分类");
        long ticketId = ticketIdOf(created.ticketNo());

        authenticateAs(itUserId, IT_SUPPORT);
        TicketActionResult claimed = ticketService.claim(
                created.ticketNo(), new ClaimTicketCommand(created.version()));
        long versionBefore = claimed.version();

        TicketActionResult changed = ticketService.changeCategory(
                created.ticketNo(),
                new ChangeCategoryCommand(versionBefore, targetCategoryId, "  分类选错了  "));

        assertThat(changed.status()).as("调整分类不改变状态").isEqualTo(PROCESSING);
        assertThat(changed.version()).isEqualTo(versionBefore + 1L);
        assertThat(changed.assignee().id()).as("负责人不变").isEqualTo(itUserId);
        assertThat(changed.actionDeadlineAt()).as("处理中本来就没有期限").isNull();

        assertThat(categoryIdOf(ticketId)).as("库里分类已替换").isEqualTo(targetCategoryId);
        assertThat(statusOf(ticketId)).isEqualTo(PROCESSING);
        assertThat(assigneeOf(ticketId)).isEqualTo(itUserId);
        assertThat(versionOf(ticketId)).isEqualTo(versionBefore + 1);
        assertThat(recordSeqOf(ticketId)).isEqualTo(3);

        assertThat(lastRecordTypeOf(ticketId)).isEqualTo("CATEGORY_CHANGE");
        assertThat(recordReason(ticketId, "CATEGORY_CHANGE"))
                .as("说明去除首尾空白后落库").isEqualTo("分类选错了");

        Map<String, Object> record = lastRecordOf(ticketId);
        assertThat(((Number) record.get("from_category_id")).longValue()).isEqualTo(categoryId);
        assertThat(((Number) record.get("to_category_id")).longValue()).isEqualTo(targetCategoryId);
        assertThat(record.get("from_status")).isEqualTo(PROCESSING);
        assertThat(record.get("to_status")).as("状态没变，两侧写同一个状态")
                .isEqualTo(PROCESSING);
        assertThat(record.get("from_priority")).as("分类调整不写优先级快照").isNull();
    }

    /** 目标分类必须启用：停用分类在服务层就被拒，且不留任何痕迹。 */
    @Test
    void changeCategoryRejectsDisabledCategoryWithoutTouchingTheRow() {
        long disabledCategoryId = insertCategory("停用分类");
        jdbc.update("UPDATE ticket_category SET status = 'DISABLED' WHERE id = ?",
                disabledCategoryId);
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        authenticateAs(itUserId, IT_SUPPORT);
        assertApiError(() -> ticketService.changeCategory(ticketNo,
                        new ChangeCategoryCommand(versionBefore, disabledCategoryId, "换分类")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        assertThat(categoryIdOf(ticketId)).isEqualTo(categoryId);
        assertThat(versionOf(ticketId)).isEqualTo(versionBefore);
        assertThat(countRecords(ticketId, "CATEGORY_CHANGE")).isZero();
    }

    /**
     * 「待补充」上调整优先级：期限原样保留。
     *
     * <p>期限是"员工还有多久要补充"的承诺，调整分类或优先级都不该让它重新计时，
     * 也不该让 {@code ck_ticket_status_deadline} 被绕过。</p>
     */
    @Test
    void changePriorityOnWaitingForRequesterKeepsSupplementDeadline() {
        long ticketId = insertWaitingForRequesterTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);
        LocalDateTime deadlineBefore = actionDeadlineOf(ticketId);

        authenticateAs(itUserId, IT_SUPPORT);
        TicketActionResult changed = ticketService.changePriority(
                ticketNo, new ChangePriorityCommand(versionBefore, "HIGH", "影响面扩大"));

        assertThat(changed.status()).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(changed.actionDeadlineAt().toLocalDateTime())
                .as("响应里的期限与调整前一致").isEqualTo(deadlineBefore);

        assertThat(priorityOf(ticketId)).isEqualTo("HIGH");
        assertThat(statusOf(ticketId)).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(actionDeadlineOf(ticketId))
                .as("ck_ticket_status_deadline：待补充仍然必须有同一个期限")
                .isEqualTo(deadlineBefore);
        assertThat(versionOf(ticketId)).isEqualTo(versionBefore + 1);

        assertThat(lastRecordTypeOf(ticketId)).isEqualTo("PRIORITY_CHANGE");
        Map<String, Object> record = lastRecordOf(ticketId);
        assertThat(record.get("from_priority")).isEqualTo("MEDIUM");
        assertThat(record.get("to_priority")).isEqualTo("HIGH");
        assertThat(record.get("from_category_id")).as("优先级调整不写分类快照").isNull();
    }

    /**
     * 转交的真库事实：负责人原子替换、状态与期限不变、新负责人被写入参与关系，
     * 因此他能按 {@code TICKET_VIEW_PARTICIPATED} 立刻看到并继续处理这张工单。
     */
    @Test
    void transferReplacesAssigneeAndGrantsVisibilityToTheNewAssignee() {
        long ticketId = insertWaitingForRequesterTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);
        LocalDateTime deadlineBefore = actionDeadlineOf(ticketId);

        authenticateAs(itUserId, IT_SUPPORT);
        TicketActionResult transferred = ticketService.transfer(ticketNo,
                new TransferCommand(versionBefore, otherItId, "  换人跟进  "));

        assertThat(transferred.status()).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(transferred.assignee().id()).as("摘要里已经是新负责人")
                .isEqualTo(otherItId);
        assertThat(transferred.version()).isEqualTo(versionBefore + 1L);
        assertThat(transferred.actionDeadlineAt().toLocalDateTime()).isEqualTo(deadlineBefore);

        assertThat(assigneeOf(ticketId)).isEqualTo(otherItId);
        assertThat(statusOf(ticketId)).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(actionDeadlineOf(ticketId)).isEqualTo(deadlineBefore);
        assertThat(lastRecordTypeOf(ticketId)).isEqualTo("TRANSFER");
        assertThat(recordReason(ticketId, "TRANSFER")).isEqualTo("换人跟进");

        Map<String, Object> record = lastRecordOf(ticketId);
        assertThat(((Number) record.get("from_assignee_id")).longValue()).isEqualTo(itUserId);
        assertThat(((Number) record.get("to_assignee_id")).longValue()).isEqualTo(otherItId);

        assertThat(participantsOf(ticketId))
                .as("新负责人的参与关系是可见性的来源，必须落库")
                .contains(otherItId);

        authenticateAs(otherItId, IT_SUPPORT);
        assertThat(ticketQueryService.detail(ticketNo).allowedActions())
                .as("接手后可以立刻撤回补充请求")
                .contains("withdraw-supplement-request");
    }

    /** 转交给提交人：服务层给出 400，不依赖数据库的 {@code ck_ticket_assignee_not_requester} 兜底。 */
    @Test
    void transferToRequesterIsRejectedWithoutTouchingTheRow() {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        authenticateAs(itUserId, IT_SUPPORT);
        assertApiError(() -> ticketService.transfer(ticketNo,
                        new TransferCommand(versionBefore, employeeId, "转给提交人")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        assertThat(assigneeOf(ticketId)).isEqualTo(itUserId);
        assertThat(versionOf(ticketId)).isEqualTo(versionBefore);
        assertThat(countRecords(ticketId, "TRANSFER")).isZero();
    }

    /**
     * 锁后复核的真实场景：目标用户的 {@code IT_SUPPORT} 角色已被撤销。
     *
     * <p>跨表资格进不了 {@code ticket} 的条件更新，只能在锁住用户行之后复核；
     * 复核失败必须回滚到"什么都没发生"，而不是把失效账号写成负责人。</p>
     */
    @Test
    void transferRejectsNewAssigneeWhoseItRoleWasRevoked() {
        long ticketId = insertWaitingForRequesterTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        long revokedItId = insertUser("revoked-it", IT_SUPPORT);
        jdbc.update("""
                DELETE user_role
                FROM iam_user_role user_role
                JOIN iam_role role ON role.id = user_role.role_id
                WHERE user_role.user_id = ? AND role.code = ?
                """, revokedItId, IT_SUPPORT);

        authenticateAs(itUserId, IT_SUPPORT);
        assertApiError(() -> ticketService.transfer(ticketNo,
                        new TransferCommand(versionBefore, revokedItId, "转给已撤权的人")),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        assertThat(assigneeOf(ticketId)).as("失败的转交不能留下任何痕迹").isEqualTo(itUserId);
        assertThat(versionOf(ticketId)).isEqualTo(versionBefore);
        assertThat(countRecords(ticketId, "TRANSFER")).isZero();
    }

    /**
     * 互转并发不死锁：两张工单同时反向转交，两个事务都要锁住同一对用户行。
     *
     * <p>若锁顺序取决于"谁转给谁"，这就是 AB-BA 死锁：InnoDB 会回滚其中一个，用户看到的是
     * 本可成功的转交失败。实现按 {@code user_id} 升序取锁，两个事务以同一顺序申请同一批行，
     * 只会排队不会成环——因此本用例断言<b>两边都成功</b>，而不是"至少一个失败"。</p>
     */
    @Test
    void concurrentTransfersInOppositeDirectionsDoNotDeadlock() throws Exception {
        long ticketOfIt = insertProcessingTicket(employeeId, itUserId);
        long ticketOfOther = insertProcessingTicket(employeeId, otherItId);
        String ticketNoOfIt = ticketNoOf(ticketOfIt);
        String ticketNoOfOther = ticketNoOf(ticketOfOther);
        long versionOfIt = versionOf(ticketOfIt);
        long versionOfOther = versionOf(ticketOfOther);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> transferAfterBarrier(barrier, ticketNoOfIt, versionOfIt, itUserId, otherItId),
                () -> transferAfterBarrier(barrier, ticketNoOfOther, versionOfOther,
                        otherItId, itUserId)));

        assertThat(results)
                .as("升序取锁下两个方向相反的转交都必须成功，结果里不应出现死锁异常")
                .allMatch(TicketActionResult.class::isInstance);
        assertThat(assigneeOf(ticketOfIt)).isEqualTo(otherItId);
        assertThat(assigneeOf(ticketOfOther)).isEqualTo(itUserId);
    }

    /** 同一张工单上「转交」与「撤回补充请求」并发：版本条件更新决定唯一赢家（片 D 起败者必须是 409）。 */
    @Test
    void concurrentTransferAndWithdrawOnSameTicketHaveExactlyOneWinner() throws Exception {
        long ticketId = insertWaitingForRequesterTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> transferAfterBarrier(barrier, ticketNo, versionBefore, itUserId, otherItId),
                () -> withdrawAfterBarrier(barrier, ticketNo, versionBefore, "信息已足够")));

        List<TicketActionResult> winners = results.stream()
                .filter(TicketActionResult.class::isInstance)
                .map(TicketActionResult.class::cast)
                .toList();
        assertThat(winners).as("同版本并发只能有一个动作成功").hasSize(1);

        /**
         * 败者只有一条路：条件更新影响 0 行、读回快照后抛 {@code 409/TICKET_CONFLICT}。
         *
         * <p>片 D 之前这里还允许第二种结果——InnoDB 死锁回滚，调用方拿到的是 {@code 500}。
         * 环来自两条路径的加锁顺序相反：转交按 {@code user_id} 升序先锁「原负责人 + 新负责人」
         * 两行 {@code iam_user}，再去改工单行；而撤回在持有工单行锁的同时，会因为
         * {@code ticket_record.actor_user_id} 的外键去申请同一条 {@code iam_user} 行的共享锁。</p>
         *
         * <p>修法是让转交也以工单行的写锁起手（{@code selectClaimConflictSnapshotForUpdate}
         * 加锁并复核版本），所有动作从此顺序一致，环消失。因此本用例不再接受死锁作为合法结果：
         * 败者必须是可立刻重试的 409，断言用 {@link #singleApiException} 收口——
         * 若败者是死锁异常，那一句会先失败。</p>
         */
        ApiException loser = singleApiException(results);
        assertThat(loser.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(loser.code()).isEqualTo("TICKET_CONFLICT");
        assertThat(loser.resourceVersion()).as("冲突响应携带库里最新版本")
                .isEqualTo(versionOf(ticketId));
        assertThat(loser.resourceStatus()).as("冲突响应携带库里最新状态")
                .isEqualTo(statusOf(ticketId));

        assertThat(versionOf(ticketId)).as("终态版本只 +1").isEqualTo(versionBefore + 1);
        assertThat(countRecords(ticketId, "TRANSFER")
                + countRecords(ticketId, "SUPPLEMENT_REQUEST_WITHDRAWN"))
                .as("两个动作只有一个落库").isEqualTo(1);
        assertThat(recordSeqOf(ticketId)).isEqualTo(4);
    }

    /** 候选人查询在真库上的排除口径：提交人与当前负责人都不在列表里。 */
    @Test
    void transferCandidatesExcludeRequesterAndCurrentAssignee() {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);

        authenticateAs(itUserId, IT_SUPPORT);
        List<TicketAssigneeOptionResult> candidates =
                ticketQueryService.transferCandidates(ticketNo);

        assertThat(candidates).extracting(TicketAssigneeOptionResult::id)
                .as("候选人必须排除提交人与当前负责人，并包含其他启用的 IT 人员")
                .doesNotContain(employeeId, itUserId)
                .contains(otherItId);
        assertThat(candidates).allSatisfy(candidate ->
                assertThat(candidate.displayName()).as("展示名不能为空").isNotBlank());
    }

    /** 与工单无关、也不是负责人的 IT 用户：不可见统一 404，不泄露工单是否存在。 */
    @Test
    void transferCandidatesAreNotFoundForUnrelatedItUser() {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long outsiderId = insertUser("outsider-it", IT_SUPPORT);

        authenticateAs(outsiderId, IT_SUPPORT);
        assertApiError(() -> ticketQueryService.transferCandidates(ticketNo),
                HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
    }

    // ---------- 片 D：结束路径 ----------

    /**
     * 人工关闭的真实写入：状态、关闭字段与结束时间必须在一条 UPDATE 里落地，
     * 时间线留一条 {@code CLOSURE}，负责人按快照保留。
     */
    @Test
    void closeWritesTerminalStateAndClosureRecord() {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        authenticateAs(itUserId, IT_SUPPORT);
        TicketActionResult closed = ticketService.close(ticketNo,
                new CloseTicketCommand(versionBefore, "OUT_OF_SCOPE", "  超出支持范围  ", null));

        assertThat(closed.status()).isEqualTo(CLOSED);
        assertThat(closed.assignee().id()).as("关闭后负责人保留为历史信息").isEqualTo(itUserId);
        assertThat(closed.actionDeadlineAt()).as("终态不再有待办期限").isNull();
        assertThat(closed.version()).isEqualTo(versionBefore + 1L);

        assertThat(statusOf(ticketId)).isEqualTo(CLOSED);
        assertThat(versionOf(ticketId)).isEqualTo(versionBefore + 1L);
        assertThat(recordSeqOf(ticketId)).isEqualTo(2);
        assertThat(endedAtOf(ticketId)).as("ck_ticket_status_ended 要求终态必须有结束时间")
                .isNotNull();
        assertThat(actionDeadlineOf(ticketId)).isNull();
        assertThat(assigneeOf(ticketId)).isEqualTo(itUserId);
        assertThat(closeMethodOf(ticketId)).as("人工关闭与超时自动关闭必须可区分")
                .isEqualTo("MANUAL");
        assertThat(closeReasonOf(ticketId)).isEqualTo("OUT_OF_SCOPE");

        Map<String, Object> record = lastRecordOf(ticketId);
        assertThat(record.get("record_type")).isEqualTo("CLOSURE");
        assertThat(record.get("reason")).as("说明去除首尾空白后进 reason 列")
                .isEqualTo("超出支持范围");
        assertThat(record.get("close_method")).isEqualTo("MANUAL");
        assertThat(record.get("close_reason")).isEqualTo("OUT_OF_SCOPE");
        assertThat(record.get("from_status")).isEqualTo(PROCESSING);
        assertThat(record.get("to_status")).isEqualTo(CLOSED);
        assertThat(countRelations(ticketId)).as("非重复关闭不写工单关联").isZero();
    }

    /**
     * 重复关闭的真实关联：{@code ticket_relation} 一条有向边，
     * source = 被关闭的本单，target = 同一提交人的另一张有效工单。
     */
    @Test
    void closeWithDuplicateTargetWritesRelation() {
        authenticateAs(employeeId, EMPLOYEE);
        TicketCreatedResult original = createTicket(UUID.randomUUID().toString(), "原始工单");
        TicketCreatedResult duplicated = createTicket(UUID.randomUUID().toString(), "重复提交的工单");

        authenticateAs(itUserId, IT_SUPPORT);
        ticketService.claim(duplicated.ticketNo(), new ClaimTicketCommand(duplicated.version()));

        long duplicatedId = ticketIdOf(duplicated.ticketNo());
        long originalId = ticketIdOf(original.ticketNo());

        TicketActionResult closed = ticketService.close(duplicated.ticketNo(),
                new CloseTicketCommand(versionOf(duplicatedId), "DUPLICATE", "与先前提交的工单重复",
                        "  " + original.ticketNo() + "  "));

        assertThat(closed.status()).isEqualTo(CLOSED);
        assertThat(statusOf(duplicatedId)).isEqualTo(CLOSED);
        assertThat(statusOf(originalId)).as("重复目标本身不受影响").isEqualTo(PENDING);

        Map<String, Object> relation = relationOf(duplicatedId);
        assertThat(((Number) relation.get("source_ticket_id")).longValue())
                .as("source 是被关闭的本单").isEqualTo(duplicatedId);
        assertThat(((Number) relation.get("target_ticket_id")).longValue()).isEqualTo(originalId);
        assertThat(relation.get("relation_type")).isEqualTo("DUPLICATE");
        assertThat(((Number) relation.get("created_by")).longValue()).isEqualTo(itUserId);
    }

    /** 目标属于别人、是自己、或已是终态：三种都按 400 拒绝，且不在本单上留下任何痕迹。 */
    @Test
    void closeRejectsInvalidDuplicateTargetsWithoutTouchingTheRow() {
        long otherEmployeeId = insertUser("other-employee", EMPLOYEE);
        long foreignTicketId = insertPendingTicket(otherEmployeeId);
        long terminalTicketId = insertPendingTicket(employeeId);
        String terminalTicketNo = ticketNoOf(terminalTicketId);

        authenticateAs(employeeId, EMPLOYEE);
        ticketService.cancel(terminalTicketNo,
                new CancelTicketCommand(versionOf(terminalTicketId), "重复提交了"));

        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        authenticateAs(itUserId, IT_SUPPORT);
        for (String target : List.of(ticketNoOf(foreignTicketId), ticketNo, terminalTicketNo)) {
            assertApiError(() -> ticketService.close(ticketNo, new CloseTicketCommand(
                            versionBefore, "DUPLICATE", "与另一张单重复", target)),
                    HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
        }

        assertThat(statusOf(ticketId)).as("失败的关闭不能留下任何痕迹").isEqualTo(PROCESSING);
        assertThat(versionOf(ticketId)).isEqualTo(versionBefore);
        assertThat(countRecords(ticketId, "CLOSURE")).isZero();
        assertThat(countRelations(ticketId)).isZero();
    }

    /** 已完成（非终态之外的正常结束方式）仍可作为重复目标：重复只说明"同一问题已有另一张单"。 */
    @Test
    void closeAcceptsCompletedTicketAsDuplicateTarget() {
        long targetId = insertWaitingForConfirmationTicket(employeeId, itUserId);
        String targetNo = ticketNoOf(targetId);

        authenticateAs(employeeId, EMPLOYEE);
        ticketService.confirmResolution(targetNo,
                new ConfirmResolutionCommand(versionOf(targetId)));
        assertThat(statusOf(targetId)).isEqualTo(COMPLETED);

        long ticketId = insertProcessingTicket(employeeId, itUserId);
        authenticateAs(itUserId, IT_SUPPORT);
        TicketActionResult closed = ticketService.close(ticketNoOf(ticketId),
                new CloseTicketCommand(versionOf(ticketId), "DUPLICATE", "与已完成的工单重复", targetNo));

        assertThat(closed.status()).isEqualTo(CLOSED);
        assertThat(((Number) relationOf(ticketId).get("target_ticket_id")).longValue())
                .isEqualTo(targetId);
    }

    /**
     * 关闭的两道闸门：缺 {@code TICKET_CLOSE} 的会话是 403；持有两条授权但不是当前负责人
     * （只是历史参与者，因此看得到这张单）是 409。
     */
    @Test
    void closeRequiresBothPermissionsAndTheCurrentAssignee() {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        // 模拟"会话里还留着部分权限"：只有 TICKET_PROCESS，没有 TICKET_CLOSE
        authenticateWith(itUserId, usernameOf(itUserId), Set.of(
                "TICKET_VIEW_QUEUE", "TICKET_VIEW_PARTICIPATED", "TICKET_PROCESS"));
        assertApiError(() -> ticketService.close(ticketNo,
                        new CloseTicketCommand(versionBefore, "INVALID", "无效工单", null)),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        // 历史参与者：看得到这张单（TICKET_VIEW_PARTICIPATED），但不是当前负责人
        insertParticipant(ticketId, otherItId);
        authenticateAs(otherItId, IT_SUPPORT);
        ApiException conflict = catchApiError(() -> ticketService.close(ticketNo,
                new CloseTicketCommand(versionBefore, "INVALID", "无效工单", null)));
        assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.code()).isEqualTo("TICKET_CONFLICT");

        assertThat(statusOf(ticketId)).isEqualTo(PROCESSING);
        assertThat(versionOf(ticketId)).isEqualTo(versionBefore);
        assertThat(countRecords(ticketId, "CLOSURE")).isZero();
    }

    /** 版本过期的关闭：{@code 409} 并带回库里真实快照，且不写终态字段。 */
    @Test
    void closeWithStaleVersionReportsCurrentSnapshot() {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);

        authenticateAs(itUserId, IT_SUPPORT);
        assertStaleConflict(() -> ticketService.close(ticketNo,
                new CloseTicketCommand(versionOf(ticketId) - 1L, "INVALID", "无效工单", null)),
                ticketId);

        assertThat(statusOf(ticketId)).isEqualTo(PROCESSING);
        assertThat(closeReasonOf(ticketId)).isNull();
    }

    /**
     * 两阶段撤销的真实写入：提交人在「待确认」发起请求，负责人批准后进入「已取消」，
     * 期限在同一条 UPDATE 里被清空，且撤销出来的终态不带任何完成/关闭字段——
     * 「已取消」「已完成」「已关闭」三态可区分。
     *
     * <p>入口取「待确认」：它是能裁决的两个状态里唯一带着自己期限的那一个（「待补充」自
     * 2026-10-10 起不裁决，真实链路见 {@code approveCancelWaitsUntilTheRequesterSupplements}），
     * 因此它同时验证两条 CHECK 都不会被撞破：请求期间状态与期限原样保留
     * （{@code ck_ticket_status_deadline}），批准时请求三列与期限一起清空
     * （{@code ck_ticket_cancel_request_pair}、{@code ck_ticket_cancel_request_status}、
     * {@code ck_ticket_status_ended}）。</p>
     */
    @Test
    void approveCancelClearsDeadlineAndKeepsTerminalStatesDistinguishable() {
        long ticketId = insertWaitingForConfirmationTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);
        LocalDateTime deadlineBefore = actionDeadlineOf(ticketId);
        assertThat(deadlineBefore).as("「待确认」本来带着期限").isNotNull();

        authenticateAs(employeeId, EMPLOYEE);
        TicketActionResult requested = ticketService.requestCancel(ticketNo,
                new RequestCancelCommand(versionBefore, "  问题已自行解决  "));

        assertThat(requested.status()).as("请求期间工单状态不变").isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(statusOf(ticketId)).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(actionDeadlineOf(ticketId)).as("请求期间工单自己的期限原样保留")
                .isEqualTo(deadlineBefore);
        assertThat(cancelRequestedAtOf(ticketId)).isNotNull();
        assertThat(cancelRequestReasonOf(ticketId)).as("撤销请求说明去掉首尾空白后落库")
                .isEqualTo("问题已自行解决");
        assertThat(cancelRequestDeadlineAtOf(ticketId)).as("请求带自己的响应期限").isNotNull();

        authenticateAs(itUserId, IT_SUPPORT);
        TicketActionResult canceled = ticketService.approveCancel(ticketNo,
                new ApproveCancelCommand(requested.version()));

        assertThat(canceled.status()).isEqualTo(CANCELED);
        assertThat(canceled.assignee().id()).as("撤销保留最后负责人").isEqualTo(itUserId);
        assertThat(canceled.actionDeadlineAt()).isNull();
        assertThat(canceled.version()).isEqualTo(versionBefore + 2L);

        assertThat(statusOf(ticketId)).isEqualTo(CANCELED);
        assertThat(actionDeadlineOf(ticketId)).as("期限在同一条 UPDATE 里被清空").isNull();
        assertThat(endedAtOf(ticketId)).isNotNull();
        assertThat(completionMethodOf(ticketId)).as("撤销不代表 IT 解决了问题").isNull();
        assertThat(closeMethodOf(ticketId)).as("撤销也不是关闭").isNull();
        assertThat(closeReasonOf(ticketId)).isNull();
        assertThat(cancelRequestedAtOf(ticketId)).as("终态不允许挂待决请求").isNull();
        assertThat(cancelRequestReasonOf(ticketId)).isNull();
        assertThat(cancelRequestDeadlineAtOf(ticketId)).isNull();
        assertThat(assigneeOf(ticketId)).isEqualTo(itUserId);

        Map<String, Object> record = lastRecordOf(ticketId);
        assertThat(record.get("record_type")).isEqualTo("CANCELLATION_APPROVED");
        assertThat(record.get("from_status")).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(record.get("to_status")).isEqualTo(CANCELED);
        assertThat(record.get("completion_method")).isNull();
        assertThat(record.get("close_method")).isNull();
    }

    /**
     * 拒绝只让请求失效：状态、负责人与终态字段全不变，之后提交人还能重新发起——拒绝不等于封死。
     *
     * <p>入口取「处理中」：拒绝与批准同源，两个可裁决状态里的另一个（「待确认」）已由批准那条
     * 用例走过真库；「待补充」自 2026-10-10 起不允许裁决，它的真实链路见
     * {@code approveCancelWaitsUntilTheRequesterSupplements}。</p>
     */
    @Test
    void rejectCancelOnProcessingKeepsTicketUntouched() {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        authenticateAs(employeeId, EMPLOYEE);
        TicketActionResult first = ticketService.requestCancel(ticketNo,
                new RequestCancelCommand(versionBefore, "先试一次"));

        assertThat(cancelRequestedAtOf(ticketId)).isNotNull();

        authenticateAs(itUserId, IT_SUPPORT);
        TicketActionResult rejected = ticketService.rejectCancel(ticketNo,
                new RejectCancelCommand(first.version(), "还有两步就能修好"));

        assertThat(rejected.status()).as("拒绝不改变状态").isEqualTo(PROCESSING);
        assertThat(statusOf(ticketId)).isEqualTo(PROCESSING);
        assertThat(actionDeadlineOf(ticketId)).as("「处理中」本来就没有期限").isNull();
        assertThat(endedAtOf(ticketId)).isNull();
        assertThat(assigneeOf(ticketId)).as("拒绝不换人").isEqualTo(itUserId);
        assertThat(cancelRequestedAtOf(ticketId)).isNull();
        assertThat(cancelRequestReasonOf(ticketId)).isNull();
        assertThat(cancelRequestDeadlineAtOf(ticketId)).isNull();
        assertThat(rejected.version()).isEqualTo(first.version() + 1L);

        Map<String, Object> rejectedRecord = lastRecordOf(ticketId);
        assertThat(rejectedRecord.get("record_type")).isEqualTo("CANCELLATION_REJECTED");
        assertThat(rejectedRecord.get("reason")).as("拒绝原因必须留在时间线上")
                .isEqualTo("还有两步就能修好");
        assertThat(rejectedRecord.get("from_status")).isEqualTo(PROCESSING);
        assertThat(rejectedRecord.get("to_status")).isEqualTo(PROCESSING);

        authenticateAs(employeeId, EMPLOYEE);
        ticketService.requestCancel(ticketNo,
                new RequestCancelCommand(rejected.version(), "还是想撤销"));

        assertThat(countRecords(ticketId, "CANCELLATION_REQUEST"))
                .as("被拒绝之后还能再发起一次").isEqualTo(2);
        assertThat(countRecords(ticketId, "CANCELLATION_REJECTED")).isEqualTo(1);
    }

    /**
     * 撤回同样只让请求失效，并且**不动「待补充」自己的期限**——两组期限彼此独立。
     *
     * <p>撤回是提交人的动作，三个状态都能做，「待补充」这一格因此仍由它覆盖。</p>
     */
    @Test
    void withdrawCancelRequestOnWaitingForRequesterKeepsTheSupplementDeadline() {
        long ticketId = insertWaitingForRequesterTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        LocalDateTime deadlineBefore = actionDeadlineOf(ticketId);

        authenticateAs(employeeId, EMPLOYEE);
        TicketActionResult requested = ticketService.requestCancel(ticketNo,
                new RequestCancelCommand(versionOf(ticketId), "先试一次"));
        TicketActionResult withdrawn = ticketService.withdrawCancelRequest(ticketNo,
                new WithdrawCancelRequestCommand(requested.version()));

        assertThat(withdrawn.status()).as("撤回不改变状态").isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(statusOf(ticketId)).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(actionDeadlineOf(ticketId)).as("撤回不改变补充期限").isEqualTo(deadlineBefore);
        assertThat(endedAtOf(ticketId)).isNull();
        assertThat(cancelRequestedAtOf(ticketId)).isNull();
        assertThat(cancelRequestReasonOf(ticketId)).isNull();
        assertThat(cancelRequestDeadlineAtOf(ticketId)).isNull();
        assertThat(withdrawn.version()).isEqualTo(requested.version() + 1L);

        assertThat(lastRecordOf(ticketId).get("record_type"))
                .isEqualTo("CANCELLATION_REQUEST_WITHDRAWN");
        assertThat(countRecords(ticketId, "CANCELLATION_REQUEST")).isEqualTo(1);
        assertThat(countRecords(ticketId, "CANCELLATION_REQUEST_WITHDRAWN")).isEqualTo(1);
    }

    /**
     * 待补充期间不裁决（2026-10-10 用户裁决）的真实链路：请求挂着、身份与版本都对，批准仍然 409；
     * 提交人补充完、工单回到「处理中」以后，同一份请求立刻可以批准。
     *
     * <p>这条同时证明"冻结的只是裁决"：待补充期间请求本身、工单期限与负责人都没被动过。</p>
     */
    @Test
    void approveCancelWaitsUntilTheRequesterSupplements() {
        long ticketId = insertWaitingForRequesterTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        LocalDateTime deadlineBefore = actionDeadlineOf(ticketId);

        authenticateAs(employeeId, EMPLOYEE);
        TicketActionResult requested = ticketService.requestCancel(ticketNo,
                new RequestCancelCommand(versionOf(ticketId), "问题已自行解决"));

        authenticateAs(itUserId, IT_SUPPORT);
        ApiException blocked = catchApiError(() -> ticketService.approveCancel(ticketNo,
                new ApproveCancelCommand(requested.version())));

        assertThat(blocked.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.code()).isEqualTo("TICKET_CONFLICT");
        assertThat(statusOf(ticketId)).as("被挡下的批准不改动工单").isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(cancelRequestedAtOf(ticketId)).as("请求仍然留在工单上").isNotNull();
        assertThat(actionDeadlineOf(ticketId)).as("补充期限也没被改动").isEqualTo(deadlineBefore);
        assertThat(countRecords(ticketId, "CANCELLATION_APPROVED")).isZero();

        authenticateAs(employeeId, EMPLOYEE);
        TicketActionResult supplemented = ticketService.supplement(ticketNo,
                new SupplementCommand(requested.version(), "型号是 L3153，报错见附件说明"));

        assertThat(supplemented.status()).isEqualTo(PROCESSING);
        assertThat(cancelRequestedAtOf(ticketId)).as("补充只推进工单，不动撤销请求").isNotNull();

        authenticateAs(itUserId, IT_SUPPORT);
        TicketActionResult canceled = ticketService.approveCancel(ticketNo,
                new ApproveCancelCommand(supplemented.version()));

        assertThat(canceled.status()).isEqualTo(CANCELED);
        assertThat(statusOf(ticketId)).isEqualTo(CANCELED);
        assertThat(lastRecordOf(ticketId).get("from_status"))
                .as("回到处理中之后批准，记录里的原状态是处理中").isEqualTo(PROCESSING);
    }

    /**
     * 待确认期间挂着待决撤销请求时，员工确认完成照样走通，并且请求三列随终态一并清空。
     *
     * <p>这条同时是 {@code ck_ticket_cancel_request_status} 的探针：确认完成是另一条进入终态的
     * 路径，忘了清请求三列数据库就会直接拒绝写入。此前只有"人工关闭"那条被
     * {@code concurrentCloseAndApproveCancelOnSameTicketHaveExactlyOneWinner} 撞过，
     * 确认完成这条一直没有组合证据。</p>
     */
    @Test
    void confirmResolutionClearsThePendingCancelRequestAndCompletesTheTicket() {
        long ticketId = insertWaitingForConfirmationTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        LocalDateTime confirmationDeadline = actionDeadlineOf(ticketId);
        assertThat(confirmationDeadline).as("「待确认」本来带着确认期限").isNotNull();

        authenticateAs(employeeId, EMPLOYEE);
        TicketActionResult requested = ticketService.requestCancel(ticketNo,
                new RequestCancelCommand(versionOf(ticketId), "  问题已自行解决  "));

        assertThat(statusOf(ticketId)).as("请求期间状态不变").isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(actionDeadlineOf(ticketId)).as("请求期间确认期限原样保留")
                .isEqualTo(confirmationDeadline);
        assertThat(cancelRequestedAtOf(ticketId)).isNotNull();
        assertThat(cancelRequestReasonOf(ticketId)).isEqualTo("问题已自行解决");

        TicketActionResult completed = ticketService.confirmResolution(ticketNo,
                new ConfirmResolutionCommand(requested.version()));

        assertThat(completed.status()).isEqualTo(COMPLETED);
        assertThat(completed.actionDeadlineAt()).as("终态不再有待办期限").isNull();
        assertThat(statusOf(ticketId)).isEqualTo(COMPLETED);
        assertThat(cancelRequestedAtOf(ticketId)).as("终态不允许挂待决请求").isNull();
        assertThat(cancelRequestReasonOf(ticketId)).isNull();
        assertThat(cancelRequestDeadlineAtOf(ticketId)).isNull();
        assertThat(actionDeadlineOf(ticketId)).as("确认完成后确认期限失效").isNull();
        assertThat(completionMethodOf(ticketId)).isEqualTo("REQUESTER_CONFIRMED");
        assertThat(endedAtOf(ticketId)).isNotNull();
        assertThat(assigneeOf(ticketId)).as("终态保留最后负责人").isEqualTo(itUserId);

        List<Map<String, Object>> rows = timelineOf(ticketId);
        assertThat(rows).extracting(row -> row.get("record_type"))
                .as("请求记录留在时间线上，确认记录排在它后面")
                .containsExactly("CREATE", "CANCELLATION_REQUEST", "COMPLETION");
        assertThat(rows).extracting(row -> ((Number) row.get("sequence_no")).intValue())
                .containsExactly(1, 2, 3);
        assertThat(recordReason(ticketId, "CANCELLATION_REQUEST"))
                .as("请求说明随记录留存").isEqualTo("问题已自行解决");
        assertThat(lastRecordTypeOf(ticketId)).isEqualTo("COMPLETION");

        // 真库探针：终态上不允许残留待决请求（ck_ticket_cancel_request_status）。
        // 三列一起写是为了先满足 ck_ticket_cancel_request_pair，把违反点留给状态白名单。
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE ticket
                SET cancel_requested_at = ?,
                    cancel_request_reason = ?,
                    cancel_request_deadline_at = ?
                WHERE id = ?
                """, confirmationDeadline, "补写一个请求", confirmationDeadline, ticketId))
                .as("ck_ticket_cancel_request_status 在真库上确实生效")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_ticket_cancel_request_status");
    }

    /**
     * 「处理中」的提交人直接撤销：服务层按冲突拒绝，条件更新本身也是 0 行。
     *
     * <p>收窄到「待受理」之后 {@code cancel} 的 {@code WHERE status = 'PENDING'} 是新加的，
     * 而服务层的状态判定会先返回 409，因此只有直接调 Mapper 才能证明那条 SQL 在真库上确实拦得住——
     * 否则"服务层拒绝"会把一条写错的 SQL 一直藏着。</p>
     */
    @Test
    void cancelIsRejectedForTheRequesterOnProcessingAndTheConditionalUpdateMatchesNoRow() {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        authenticateAs(employeeId, EMPLOYEE);
        assertStaleConflict(() -> ticketService.cancel(ticketNo,
                new CancelTicketCommand(versionBefore, "问题已自行解决")), ticketId);

        assertThat(ticketMapper.cancel(ticketId, versionBefore, employeeId,
                LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS)))
                .as("条件更新里的状态白名单也拦住了这次写入")
                .isZero();
        assertThat(statusOf(ticketId)).isEqualTo(PROCESSING);
        assertThat(versionOf(ticketId)).isEqualTo(versionBefore);
        assertThat(countRecords(ticketId, "CANCELLATION")).isZero();
    }

    /** 待受理没有负责人，撤销后仍然是 null：{@code ck_ticket_status_assignee} 两种都允许。 */
    @Test
    void cancelOfPendingTicketKeepsNullAssignee() {
        long ticketId = insertPendingTicket(employeeId);
        String ticketNo = ticketNoOf(ticketId);

        authenticateAs(employeeId, EMPLOYEE);
        TicketActionResult canceled = ticketService.cancel(ticketNo,
                new CancelTicketCommand(versionOf(ticketId), "已经不需要了"));

        assertThat(canceled.status()).isEqualTo(CANCELED);
        assertThat(canceled.assignee()).isNull();
        assertThat(statusOf(ticketId)).isEqualTo(CANCELED);
        assertThat(assigneeOf(ticketId)).isNull();
        assertThat(countRecords(ticketId, "CANCELLATION")).isEqualTo(1);
    }

    /**
     * 领取之后 IT 是负责人，但提交人始终是员工：IT 撤不掉这张单。
     *
     * <p>即使给 IT 会话补上 {@code TICKET_REQUESTER_ACTION}，身份判定仍然按 409 拒绝——
     * 这正是用户 2026-10-08 追问的那一格。</p>
     */
    @Test
    void cancelIsRejectedForTheCurrentAssignee() {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);

        authenticateWith(itUserId, usernameOf(itUserId), Set.of(
                "TICKET_VIEW_QUEUE", "TICKET_VIEW_PARTICIPATED", "TICKET_PROCESS",
                "TICKET_CLOSE", "TICKET_REQUESTER_ACTION"));
        ApiException conflict = catchApiError(() -> ticketService.cancel(ticketNo,
                new CancelTicketCommand(versionBefore, "IT 想撤销")));
        assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.code()).isEqualTo("TICKET_CONFLICT");

        assertThat(statusOf(ticketId)).isEqualTo(PROCESSING);
        assertThat(versionOf(ticketId)).isEqualTo(versionBefore);
        assertThat(countRecords(ticketId, "CANCELLATION")).isZero();
    }

    /** 没有提交人权限的账号连可见性都不查：闸门是 403，不是 404。 */
    @Test
    void cancelRejectsActorWithoutRequesterActionAuthority() {
        long ticketId = insertPendingTicket(employeeId);
        String ticketNo = ticketNoOf(ticketId);

        authenticateWith(employeeId, usernameOf(employeeId), Set.of("TICKET_VIEW_OWN"));
        assertApiError(() -> ticketService.cancel(ticketNo,
                        new CancelTicketCommand(versionOf(ticketId), "已经不需要了")),
                HttpStatus.FORBIDDEN, "TICKET_ACTION_FORBIDDEN");

        assertThat(statusOf(ticketId)).isEqualTo(PENDING);
    }

    /** 终态再撤销：{@code 409} 并带回当前快照，不会覆盖第一次结束的结果。 */
    @Test
    void cancelOnTerminalTicketReportsCurrentSnapshot() {
        long ticketId = insertPendingTicket(employeeId);
        String ticketNo = ticketNoOf(ticketId);

        authenticateAs(employeeId, EMPLOYEE);
        TicketActionResult first = ticketService.cancel(ticketNo,
                new CancelTicketCommand(versionOf(ticketId), "已经不需要了"));
        long versionAfterCancel = versionOf(ticketId);

        assertStaleConflict(() -> ticketService.cancel(ticketNo,
                new CancelTicketCommand(first.version(), "再撤一次")), ticketId);

        assertThat(versionOf(ticketId)).as("失败的撤销不改版本").isEqualTo(versionAfterCancel);
        assertThat(countRecords(ticketId, "CANCELLATION")).isEqualTo(1);
    }

    /** 终态没有出口：已关闭的工单不能再被领取。 */
    @Test
    void closedTicketCannotBeClaimedOrClosedAgain() {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);

        authenticateAs(itUserId, IT_SUPPORT);
        ticketService.close(ticketNo,
                new CloseTicketCommand(versionOf(ticketId), "INVALID", "无效工单", null));
        long versionAfterClose = versionOf(ticketId);

        assertStaleConflict(() -> ticketService.close(ticketNo,
                new CloseTicketCommand(versionAfterClose, "INVALID", "再关一次", null)), ticketId);
        assertThat(versionOf(ticketId)).isEqualTo(versionAfterClose);
        assertThat(countRecords(ticketId, "CLOSURE")).isEqualTo(1);
    }

    /**
     * 同一张挂着待决撤销请求的「处理中」工单上「IT 关闭」与「IT 批准撤销」并发：
     * 恰好一个赢家，败者必须是 409。
     *
     * <p>两个动作都从工单行的条件更新起手，都要求工单上存在待决请求（关闭不受它阻挡，
     * 见设计点④）；输的一方读回快照后按 {@code TICKET_CONFLICT} 返回。这条用例同时也是
     * "两个结束路径不会互相覆盖"的真库证据——终态字段与记录只属于赢家，
     * 待决请求三列也随终态一起清空（{@code ck_ticket_cancel_request_status}）。</p>
     */
    @Test
    void concurrentCloseAndApproveCancelOnSameTicketHaveExactlyOneWinner() throws Exception {
        long ticketId = insertProcessingTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);

        authenticateAs(employeeId, EMPLOYEE);
        TicketActionResult requested = ticketService.requestCancel(ticketNo,
                new RequestCancelCommand(versionOf(ticketId), "并发撤销"));
        long versionBefore = requested.version();

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> closeAfterBarrier(barrier, ticketNo, versionBefore),
                () -> approveCancelAfterBarrier(barrier, ticketNo, versionBefore)));

        List<TicketActionResult> winners = results.stream()
                .filter(TicketActionResult.class::isInstance)
                .map(TicketActionResult.class::cast)
                .toList();
        assertThat(winners).as("同版本并发只能有一个动作成功").hasSize(1);

        ApiException loser = singleApiException(results);
        assertThat(loser.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(loser.code()).isEqualTo("TICKET_CONFLICT");
        assertThat(loser.resourceVersion()).as("冲突响应携带库里最新版本")
                .isEqualTo(versionOf(ticketId));

        assertThat(versionOf(ticketId)).as("终态版本只 +1").isEqualTo(versionBefore + 1);
        assertThat(countRecords(ticketId, "CLOSURE")
                + countRecords(ticketId, "CANCELLATION_APPROVED"))
                .as("两个结束动作只有一个落库").isEqualTo(1);
        assertThat(cancelRequestedAtOf(ticketId)).as("终态不留下待决请求").isNull();
        assertThat(statusOf(ticketId)).isIn(CLOSED, CANCELED);
    }

    /** 待受理上「员工撤销」与「IT 领取」并发：同样是唯一胜者，败者 409。 */
    @Test
    void concurrentClaimAndCancelOnSameTicketHaveExactlyOneWinner() throws Exception {
        long ticketId = insertPendingTicket(employeeId);
        String ticketNo = ticketNoOf(ticketId);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> claimAfterBarrier(barrier, itUserId, ticketNo),
                () -> cancelAfterBarrier(barrier, ticketNo, 0L)));

        List<TicketActionResult> winners = results.stream()
                .filter(TicketActionResult.class::isInstance)
                .map(TicketActionResult.class::cast)
                .toList();
        assertThat(winners).as("领取与撤销只能有一个成功").hasSize(1);

        ApiException loser = singleApiException(results);
        assertThat(loser.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(loser.code()).isEqualTo("TICKET_CONFLICT");

        assertThat(versionOf(ticketId)).isEqualTo(1L);
        assertThat(statusOf(ticketId)).as("赢家只有一个：要么被领走，要么被撤销")
                .isIn(PROCESSING, CANCELED);
        if (CANCELED.equals(statusOf(ticketId))) {
            assertThat(assigneeOf(ticketId)).as("没有赢家的领取不能留下负责人").isNull();
        }
    }

    // ---------- 并发动作辅助 ----------

    private Object claimAfterBarrier(CyclicBarrier barrier, long actorId, String ticketNo)
            throws Exception {
        barrier.await(30, TimeUnit.SECONDS);
        authenticateAs(actorId, IT_SUPPORT);
        return ticketService.claim(ticketNo, new ClaimTicketCommand(0L));
    }

    private Object processAfterBarrier(
            CyclicBarrier barrier, String ticketNo, long version, String content) throws Exception {
        barrier.await(30, TimeUnit.SECONDS);
        authenticateAs(itUserId, IT_SUPPORT);
        return ticketService.addProcessingRecord(
                ticketNo, new AddProcessingRecordCommand(version, content));
    }

    private Object resolveAfterBarrier(
            CyclicBarrier barrier, String ticketNo, long version, String content) throws Exception {
        barrier.await(30, TimeUnit.SECONDS);
        authenticateAs(itUserId, IT_SUPPORT);
        return ticketService.submitResolution(
                ticketNo, new SubmitResolutionCommand(version, content));
    }

    private Object confirmAfterBarrier(CyclicBarrier barrier, String ticketNo, long version)
            throws Exception {
        barrier.await(30, TimeUnit.SECONDS);
        authenticateAs(employeeId, EMPLOYEE);
        return ticketService.confirmResolution(ticketNo, new ConfirmResolutionCommand(version));
    }

    private Object withdrawAfterBarrier(
            CyclicBarrier barrier, String ticketNo, long version, String reason) throws Exception {
        barrier.await(30, TimeUnit.SECONDS);
        authenticateAs(itUserId, IT_SUPPORT);
        return ticketService.withdrawSupplementRequest(
                ticketNo, new WithdrawSupplementRequestCommand(version, reason));
    }

    private Object requestSupplementAfterBarrier(
            CyclicBarrier barrier, String ticketNo, long version, String content) throws Exception {
        barrier.await(30, TimeUnit.SECONDS);
        authenticateAs(itUserId, IT_SUPPORT);
        return ticketService.requestSupplement(
                ticketNo, new RequestSupplementCommand(version, content));
    }

    private Object reportAfterBarrier(
            CyclicBarrier barrier, String ticketNo, long version, String reason) throws Exception {
        barrier.await(30, TimeUnit.SECONDS);
        authenticateAs(employeeId, EMPLOYEE);
        return ticketService.reportUnresolved(
                ticketNo, new ReportUnresolvedCommand(version, reason));
    }

    private Object transferAfterBarrier(
            CyclicBarrier barrier, String ticketNo, long version,
            long actorId, long newAssigneeId) throws Exception {
        barrier.await(30, TimeUnit.SECONDS);
        authenticateAs(actorId, IT_SUPPORT);
        return ticketService.transfer(ticketNo,
                new TransferCommand(version, newAssigneeId, "并发转交"));
    }

    private Object closeAfterBarrier(CyclicBarrier barrier, String ticketNo, long version)
            throws Exception {
        barrier.await(30, TimeUnit.SECONDS);
        authenticateAs(itUserId, IT_SUPPORT);
        return ticketService.close(ticketNo,
                new CloseTicketCommand(version, "INVALID", "并发关闭", null));
    }

    private Object cancelAfterBarrier(CyclicBarrier barrier, String ticketNo, long version)
            throws Exception {
        barrier.await(30, TimeUnit.SECONDS);
        authenticateAs(employeeId, EMPLOYEE);
        return ticketService.cancel(ticketNo,
                new CancelTicketCommand(version, "并发撤销"));
    }

    private Object approveCancelAfterBarrier(CyclicBarrier barrier, String ticketNo, long version)
            throws Exception {
        barrier.await(30, TimeUnit.SECONDS);
        authenticateAs(itUserId, IT_SUPPORT);
        return ticketService.approveCancel(ticketNo, new ApproveCancelCommand(version));
    }

    // ---------- 身份与断言辅助 ----------

    private TicketCreatedResult createTicket(String submissionKey, String title) {
        return ticketService.create(new CreateTicketCommand(
                submissionKey, title, title + "-描述", categoryId, "MEDIUM"));
    }

    /** 与 {@code JwtAuthenticationFilter} 一致：权限来自会话快照，由真实角色授权推导。 */
    private void authenticateAs(long userId, String... roleCodes) {
        List<String> authorities = jdbc.queryForList("""
                SELECT DISTINCT permission.code
                FROM iam_user_role user_role
                JOIN iam_role_permission role_permission
                  ON role_permission.role_id = user_role.role_id
                JOIN iam_permission permission
                  ON permission.id = role_permission.permission_id
                WHERE user_role.user_id = ?
                ORDER BY permission.code
                """, String.class, userId);
        authenticateWith(userId, usernameOf(userId), new LinkedHashSet<>(authorities));
    }

    /**
     * 直接给定权限集合：用于模拟"会话里还留着某项权限、但数据库里的角色授权已经变了"的竞态。
     */
    private void authenticateWith(long userId, String username, Set<String> authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(userId, username, "itkt-session"),
                        null,
                        authorities.stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private ApiException singleApiException(List<Object> results) {
        List<ApiException> failures = results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .toList();
        assertThat(failures).as("并发结果里必须恰好有一个失败").hasSize(1);
        return failures.getFirst();
    }

    private void assertStaleConflict(ThrowingCallable invocation, long ticketId) {
        ApiException conflict = catchApiError(invocation);
        assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.code()).isEqualTo("TICKET_CONFLICT");
        assertThat(conflict.resourceVersion())
                .as("冲突快照必须等于库里最新版本")
                .isEqualTo(versionOf(ticketId));
        assertThat(conflict.resourceStatus())
                .as("冲突快照必须等于库里最新状态")
                .isEqualTo(statusOf(ticketId));
    }

    private static ApiException catchApiError(ThrowingCallable invocation) {
        ApiException exception = catchThrowableOfType(invocation, ApiException.class);
        assertThat(exception).isNotNull();
        return exception;
    }

    private void assertApiError(
            ThrowingCallable invocation, HttpStatus expectedStatus, String expectedCode) {
        ApiException exception = catchApiError(invocation);
        assertThat(exception.status()).isEqualTo(expectedStatus);
        assertThat(exception.code()).isEqualTo(expectedCode);
    }

    /** 同时启动所有任务，返回与入参顺序一致的结果或异常，交由用例断言。 */
    private static List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> task : tasks) {
                futures.add(executor.submit(task));
            }
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                try {
                    results.add(future.get(60, TimeUnit.SECONDS));
                } catch (java.util.concurrent.ExecutionException exception) {
                    results.add(exception.getCause());
                }
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    // ---------- 造数辅助 ----------

    private long insertUser(String suffix, String... roleCodes) {
        String username = USERNAME_PREFIX + suffix;
        jdbc.update("""
                INSERT INTO iam_user (
                    username, display_name, password, status,
                    created_at, updated_at, version
                ) VALUES (?, ?, 'integration-test-hash', 'ENABLED',
                          CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0)
                """, username, username);
        long userId = jdbc.queryForObject(
                "SELECT id FROM iam_user WHERE username = ?", Long.class, username);
        for (String roleCode : roleCodes) {
            jdbc.update("""
                    INSERT INTO iam_user_role (user_id, role_id, granted_by, granted_at)
                    SELECT ?, id, NULL, CURRENT_TIMESTAMP(3) FROM iam_role WHERE code = ?
                    """, userId, roleCode);
        }
        return userId;
    }

    private long insertCategory(String suffix) {
        String name = CATEGORY_PREFIX + suffix;
        jdbc.update("""
                INSERT INTO ticket_category (
                    name, status, sort_order, created_at, updated_at, version
                ) VALUES (?, 'ENABLED', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0)
                """, name);
        return jdbc.queryForObject(
                "SELECT id FROM ticket_category WHERE name = ?", Long.class, name);
    }

    private long insertPendingTicket(long requesterId) {
        long ticketId = insertTicketRow(requesterId, PENDING, null, null);
        insertCreateRecord(ticketId, requesterId);
        return ticketId;
    }

    private long insertProcessingTicket(long requesterId, long assigneeId) {
        long ticketId = insertTicketRow(requesterId, PROCESSING, assigneeId, null);
        insertCreateRecord(ticketId, requesterId);
        return ticketId;
    }

    private long insertWaitingForConfirmationTicket(long requesterId, long assigneeId) {
        LocalDateTime deadline = LocalDateTime.now(ZoneOffset.UTC)
                .plus(CONFIRMATION_WINDOW).truncatedTo(ChronoUnit.MILLIS);
        long ticketId = insertTicketRow(requesterId, WAITING_FOR_CONFIRMATION, assigneeId, deadline);
        insertCreateRecord(ticketId, requesterId);
        return ticketId;
    }

    /**
     * 用真实动作把工单推进到「待补充」：建单 → 领取 → 请求补充。
     *
     * <p>片 A 期间这里直接改库置位（当时没有任何接口能进入该状态）。片 B 之后改为走
     * {@code request-supplement}，因此版本号、{@code record_seq} 与期限都来自真实链路，
     * 用例断言的起点不再是自己拼出来的行。</p>
     *
     * <p>返回时把身份留在 {@code itUserId}（与请求补充的执行者一致），调用方按需重新认证。</p>
     */
    private long insertWaitingForRequesterTicket(long requesterId, long assigneeId) {
        authenticateAs(requesterId, EMPLOYEE);
        TicketCreatedResult created = createTicket(UUID.randomUUID().toString(), "待补充工单");

        authenticateAs(assigneeId, IT_SUPPORT);
        TicketActionResult claimed = ticketService.claim(
                created.ticketNo(), new ClaimTicketCommand(created.version()));

        ticketService.requestSupplement(created.ticketNo(),
                new RequestSupplementCommand(claimed.version(), "请补充打印机型号与错误截图"));

        return ticketIdOf(created.ticketNo());
    }

    /** 造一张 record_seq = 1 的工单，与创建动作落库后的形状一致。 */
    private long insertTicketRow(long requesterId, String status, Long assigneeId,
                                 LocalDateTime actionDeadlineAt) {
        String ticketNo = "FD-20260101-" + FIXTURE_SEQUENCE.incrementAndGet();
        jdbc.update("""
                INSERT INTO ticket (
                    ticket_no, submission_key, requester_id, title, description,
                    category_id, priority, status, assignee_id, action_deadline_at,
                    record_seq, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'MEDIUM', ?, ?, ?, 1, 0,
                          CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """, ticketNo, UUID.randomUUID().toString(), requesterId,
                "造数工单-" + ticketNo, "造数工单描述", categoryId, status, assigneeId,
                actionDeadlineAt);
        return ticketIdOf(ticketNo);
    }

    private void insertCreateRecord(long ticketId, long requesterId) {
        jdbc.update("""
                INSERT INTO ticket_record (
                    ticket_id, sequence_no, record_type, actor_type, actor_user_id,
                    to_status, to_category_id, to_priority, created_at
                ) VALUES (?, 1, 'CREATE', 'USER', ?, 'PENDING', ?, 'MEDIUM', CURRENT_TIMESTAMP(3))
                """, ticketId, requesterId, categoryId);
    }

    private void insertParticipant(long ticketId, long userId) {
        jdbc.update("""
                INSERT INTO ticket_participant (
                    ticket_id, user_id, first_assigned_at, last_assigned_at
                ) VALUES (?, ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """, ticketId, userId);
    }

    // ---------- 直查辅助 ----------

    private long ticketIdOf(String ticketNo) {
        Long ticketId = jdbc.queryForObject(
                "SELECT id FROM ticket WHERE ticket_no = ?", Long.class, ticketNo);
        assertThat(ticketId).as("工单必须存在：" + ticketNo).isNotNull();
        return ticketId;
    }

    private String ticketNoOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT ticket_no FROM ticket WHERE id = ?", String.class, ticketId);
    }

    private String usernameOf(long userId) {
        return jdbc.queryForObject(
                "SELECT username FROM iam_user WHERE id = ?", String.class, userId);
    }

    private String titleOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT title FROM ticket WHERE id = ?", String.class, ticketId);
    }

    private String statusOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT status FROM ticket WHERE id = ?", String.class, ticketId);
    }

    private Long assigneeOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT assignee_id FROM ticket WHERE id = ?", Long.class, ticketId);
    }

    private long categoryIdOf(long ticketId) {
        Long categoryId = jdbc.queryForObject(
                "SELECT category_id FROM ticket WHERE id = ?", Long.class, ticketId);
        return categoryId == null ? -1L : categoryId;
    }

    private String priorityOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT priority FROM ticket WHERE id = ?", String.class, ticketId);
    }

    /** 时间线最后一条记录的完整行：用于断言 from/to 快照列，而不只是记录类型。 */
    private Map<String, Object> lastRecordOf(long ticketId) {
        return jdbc.queryForMap("""
                SELECT * FROM ticket_record
                WHERE ticket_id = ?
                ORDER BY sequence_no DESC
                LIMIT 1
                """, ticketId);
    }

    private List<Long> participantsOf(long ticketId) {
        return jdbc.queryForList(
                "SELECT user_id FROM ticket_participant WHERE ticket_id = ? ORDER BY user_id",
                Long.class, ticketId);
    }

    private long versionOf(long ticketId) {
        Long version = jdbc.queryForObject(
                "SELECT version FROM ticket WHERE id = ?", Long.class, ticketId);
        return version == null ? -1L : version;
    }

    private int recordSeqOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT record_seq FROM ticket WHERE id = ?", Integer.class, ticketId);
    }

    private LocalDateTime actionDeadlineOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT action_deadline_at FROM ticket WHERE id = ?", LocalDateTime.class, ticketId);
    }

    private LocalDateTime endedAtOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT ended_at FROM ticket WHERE id = ?", LocalDateTime.class, ticketId);
    }

    private LocalDateTime cancelRequestedAtOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT cancel_requested_at FROM ticket WHERE id = ?",
                LocalDateTime.class, ticketId);
    }

    private String cancelRequestReasonOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT cancel_request_reason FROM ticket WHERE id = ?", String.class, ticketId);
    }

    private LocalDateTime cancelRequestDeadlineAtOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT cancel_request_deadline_at FROM ticket WHERE id = ?",
                LocalDateTime.class, ticketId);
    }

    private String completionMethodOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT completion_method FROM ticket WHERE id = ?", String.class, ticketId);
    }

    private String closeMethodOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT close_method FROM ticket WHERE id = ?", String.class, ticketId);
    }

    private String closeReasonOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT close_reason FROM ticket WHERE id = ?", String.class, ticketId);
    }

    /** 一份工单作为 source 的关联行；没有关联时 {@code queryForMap} 会抛异常，正是"必须存在"的断言。 */
    private Map<String, Object> relationOf(long sourceTicketId) {
        return jdbc.queryForMap("""
                SELECT source_ticket_id, target_ticket_id, relation_type, created_by
                FROM ticket_relation WHERE source_ticket_id = ?
                """, sourceTicketId);
    }

    private int countRelations(long sourceTicketId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_relation WHERE source_ticket_id = ?",
                Integer.class, sourceTicketId);
    }

    private int countRecords(long ticketId, String recordType) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM ticket_record WHERE ticket_id = ? AND record_type = ?
                """, Integer.class, ticketId, recordType);
    }

    private int recordSequenceOf(long ticketId, String recordType) {
        return jdbc.queryForObject("""
                SELECT sequence_no FROM ticket_record WHERE ticket_id = ? AND record_type = ?
                """, Integer.class, ticketId, recordType);
    }

    private String recordContent(long ticketId, String recordType) {
        return jdbc.queryForObject("""
                SELECT content FROM ticket_record WHERE ticket_id = ? AND record_type = ?
                """, String.class, ticketId, recordType);
    }

    private String recordReason(long ticketId, String recordType) {
        return jdbc.queryForObject("""
                SELECT reason FROM ticket_record WHERE ticket_id = ? AND record_type = ?
                """, String.class, ticketId, recordType);
    }

    /** 记录自己冻结的期限：请求补充写值，补充完成不写值。 */
    private LocalDateTime recordDeadlineOf(long ticketId, String recordType) {
        return jdbc.queryForObject("""
                SELECT deadline_at FROM ticket_record WHERE ticket_id = ? AND record_type = ?
                """, LocalDateTime.class, ticketId, recordType);
    }

    /** 时间线按序号升序：sequence_no 是 INT UNSIGNED，比较前先归一化成 int。 */
    private List<Map<String, Object>> timelineOf(long ticketId) {
        return jdbc.queryForList("""
                SELECT sequence_no, record_type, content, reason
                FROM ticket_record WHERE ticket_id = ? ORDER BY sequence_no ASC
                """, ticketId);
    }

    private String lastRecordTypeOf(long ticketId) {
        return jdbc.queryForObject("""
                SELECT record_type FROM ticket_record
                WHERE ticket_id = ? ORDER BY sequence_no DESC LIMIT 1
                """, String.class, ticketId);
    }

    private int countParticipants(long ticketId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_participant WHERE ticket_id = ?",
                Integer.class, ticketId);
    }

    private Long participantUserId(long ticketId) {
        return jdbc.queryForObject("""
                SELECT user_id FROM ticket_participant WHERE ticket_id = ?
                """, Long.class, ticketId);
    }

    private int currentSequence(LocalDate businessDate) {
        Long value = jdbc.queryForObject("""
                SELECT current_value FROM ticket_daily_sequence WHERE business_date = ?
                """, Long.class, businessDate);
        return value == null ? 0 : Math.toIntExact(value);
    }

    private long dailySequenceTotal() {
        return jdbc.query("SELECT business_date, current_value FROM ticket_daily_sequence",
                resultSet -> {
                    Map<LocalDate, Long> values = new TreeMap<>();
                    while (resultSet.next()) {
                        values.put(resultSet.getDate("business_date").toLocalDate(),
                                resultSet.getLong("current_value"));
                    }
                    return values.values().stream().mapToLong(Long::longValue).sum();
                });
    }
}
