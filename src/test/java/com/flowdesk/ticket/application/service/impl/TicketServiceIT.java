package com.flowdesk.ticket.application.service.impl;

import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.ticket.application.command.AddProcessingRecordCommand;
import com.flowdesk.ticket.application.command.ClaimTicketCommand;
import com.flowdesk.ticket.application.command.ConfirmResolutionCommand;
import com.flowdesk.ticket.application.command.CreateTicketCommand;
import com.flowdesk.ticket.application.command.ReportUnresolvedCommand;
import com.flowdesk.ticket.application.command.SubmitResolutionCommand;
import com.flowdesk.ticket.application.command.WithdrawSupplementRequestCommand;
import com.flowdesk.ticket.application.query.TicketRecordQuery;
import com.flowdesk.ticket.application.result.TicketActionResult;
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
 * <p><b>片 A（退回处理中）的临时造数手段</b>：{@code withdrawSupplementRequest} 要求工单处于
 * {@code WAITING_FOR_REQUESTER}，而进入该状态要靠片 B 的 {@code request-supplement}——
 * 当前没有任何接口可以做到。因此 {@link #insertWaitingForRequesterTicket} 用 {@link JdbcTemplate}
 * 直接把一张已领取的工单置位（状态、期限 = now + 7d、{@code version + 1}、{@code record_seq + 1}），
 * 并补一条 {@code SUPPLEMENT_REQUEST} 记录保持时间线连贯，形状与一次真实动作落库后一致。
 * 片 B 落地后这些用例应改为走 {@code request-supplement} 造数，本注释随之失效。</p>
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

    /** 与 {@code application.yml} 的 {@code flowdesk.ticket.confirmation-window} 一致。 */
    private static final Duration CONFIRMATION_WINDOW = Duration.ofDays(7);

    /**
     * 「待补充」造数用的期限。
     *
     * <p>片 B 之前没有 {@code flowdesk.ticket.supplement-window} 配置，也没有任何接口能进入
     * {@link #WAITING_FOR_REQUESTER}，因此这里固定按 7×24 小时造数；片 B 落地后应改为
     * 走 {@code request-supplement} 接口并使用它自己的窗口配置。</p>
     */
    private static final Duration SUPPLEMENT_FIXTURE_WINDOW = Duration.ofDays(7);

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
     * <p>工单由 {@link #insertWaitingForRequesterTicket} 直接造数——片 B 之前没有接口能进入
     * {@code WAITING_FOR_REQUESTER}，该方法是临时手段，片 B 落地后改用
     * {@code request-supplement}。</p>
     */
    @Test
    void withdrawSupplementRequestReturnsTicketToProcessingAndClearsSupplementDeadline() {
        long ticketId = insertWaitingForRequesterTicket(employeeId, itUserId);
        String ticketNo = ticketNoOf(ticketId);
        long versionBefore = versionOf(ticketId);
        LocalDateTime supplementDeadline = actionDeadlineOf(ticketId);

        assertThat(statusOf(ticketId)).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(supplementDeadline)
                .as("造数必须满足 ck_ticket_status_deadline：待补充要有期限").isNotNull();

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
        assertThat(recordSeqOf(ticketId)).isEqualTo(3);

        List<Map<String, Object>> rows = timelineOf(ticketId);
        assertThat(rows).extracting(row -> ((Number) row.get("sequence_no")).intValue())
                .as("补的 SUPPLEMENT_REQUEST 与撤回记录接在同一条时间线上")
                .containsExactly(1, 2, 3);
        assertThat(rows).extracting(row -> row.get("record_type"))
                .containsExactly("CREATE", "SUPPLEMENT_REQUEST",
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
        assertThat(recordSeqOf(ticketId)).isEqualTo(3);
        assertThat(actionDeadlineOf(ticketId))
                .as("ck_ticket_status_deadline：退回处理中后期限为 NULL").isNull();
        assertThat(recordReason(ticketId, "SUPPLEMENT_REQUEST_WITHDRAWN"))
                .as("落库原因只能来自其中一个赢家")
                .isIn("第一条撤回原因", "第二条撤回原因");
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
        assertThat(versionOf(ticketId)).isEqualTo(1);
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

    private Object reportAfterBarrier(
            CyclicBarrier barrier, String ticketNo, long version, String reason) throws Exception {
        barrier.await(30, TimeUnit.SECONDS);
        authenticateAs(employeeId, EMPLOYEE);
        return ticketService.reportUnresolved(
                ticketNo, new ReportUnresolvedCommand(version, reason));
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
     * 片 B 之前的临时造数：没有接口能进入 {@code WAITING_FOR_REQUESTER}，
     * 因此直接把一张已领取工单置位，并补一条 {@code SUPPLEMENT_REQUEST} 记录保持时间线连贯。
     *
     * <p>形状与一次真实动作落库后一致：{@code version + 1}、{@code record_seq + 1}、
     * 期限 = now + 7d（满足 {@code ck_ticket_status_deadline} 与 {@code ck_ticket_status_assignee}）。
     * 片 B 的 {@code request-supplement} 落地后应改为走接口造数。</p>
     */
    private long insertWaitingForRequesterTicket(long requesterId, long assigneeId) {
        LocalDateTime deadline = LocalDateTime.now(ZoneOffset.UTC)
                .plus(SUPPLEMENT_FIXTURE_WINDOW).truncatedTo(ChronoUnit.MILLIS);
        long ticketId = insertTicketRow(requesterId, WAITING_FOR_REQUESTER, assigneeId, deadline);
        insertCreateRecord(ticketId, requesterId);
        jdbc.update("""
                UPDATE ticket
                SET version = version + 1,
                    record_seq = record_seq + 1,
                    updated_at = CURRENT_TIMESTAMP(3)
                WHERE id = ?
                """, ticketId);
        jdbc.update("""
                INSERT INTO ticket_record (
                    ticket_id, sequence_no, record_type, actor_type, actor_user_id,
                    content, from_status, to_status, deadline_at, created_at
                ) VALUES (?, 2, 'SUPPLEMENT_REQUEST', 'USER', ?, ?, 'PROCESSING',
                          'WAITING_FOR_REQUESTER', ?, CURRENT_TIMESTAMP(3))
                """, ticketId, assigneeId, "请补充打印机型号与错误截图", deadline);
        return ticketId;
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

    private String completionMethodOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT completion_method FROM ticket WHERE id = ?", String.class, ticketId);
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
