package com.flowdesk.ticket.application.service.impl;

import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.notification.domain.NotificationCategory;
import com.flowdesk.ticket.application.command.ClaimTicketCommand;
import com.flowdesk.ticket.application.command.RequestCancelCommand;
import com.flowdesk.ticket.application.command.RequestSupplementCommand;
import com.flowdesk.ticket.application.result.TicketActionResult;
import com.flowdesk.ticket.application.result.TicketCreatedResult;
import com.flowdesk.ticket.application.service.TicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时自动处置、到期前提醒与提醒回收在真实 MySQL 上的行为。
 *
 * <p>替身测不出这条链路的真正难点：条件更新命中 0 行的 0 行是真的、
 * {@code FOR UPDATE} 重读的判据是真的、{@code uk_notification_recipient_dedup}
 * 的去重是数据库保证的。本类在真库上钉住三件事——</p>
 *
 * <ul>
 *   <li><b>自动完成 / 自动关闭真的改库</b>：状态、终态字段、{@code ended_at}、{@code version}、
 *       {@code record_seq} 一次更新到位，留痕与通知同事务落库；</li>
 *   <li><b>已被人工动作抢先 → 0 行静默跳过</b>：把期限改到过去造成"扫描看得见、更新打不着"，
 *       断言既不改状态也不落通知；</li>
 *   <li><b>提醒的去重与回收</b>：同一期限重复跑只留一条；期限被改写后键不同、可再发；
 *       动作触发时按类别回收，撤销请求自然过期由超时任务兜底回收。</li>
 * </ul>
 *
 * <p>独立于 {@code TicketServiceIT}：那条链路已有 100+ 用例、夹具前缀与清理逻辑固定，
 * 塞进来会污染它的事务与清理边界。这里沿用相同的 Testcontainers、身份与清理约定，
 * 但用「直接改库把期限挪到过去」造超时条件——这是唯一能在不等待真实 7 天的情况下
 * 让条件更新真正命中的方式。</p>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
class TicketTimeoutServiceIT {

    private static final String MYSQL_IMAGE = "mysql:8.4.11";
    private static final String USERNAME_PREFIX = "ittm-";
    private static final String CATEGORY_PREFIX = "ITTM-";

    private static final String EMPLOYEE = "EMPLOYEE";
    private static final String IT_SUPPORT = "IT_SUPPORT";

    private static final String PROCESSING = "PROCESSING";
    private static final String WAITING_FOR_REQUESTER = "WAITING_FOR_REQUESTER";
    private static final String WAITING_FOR_CONFIRMATION = "WAITING_FOR_CONFIRMATION";
    private static final String COMPLETED = "COMPLETED";
    private static final String CLOSED = "CLOSED";

    private static final int BATCH = 200;

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("flowdesk_ticket_timeout_it")
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

    @Autowired
    private JdbcTemplate jdbc;

    /** Redis 不属于本切片，用替身满足认证模块依赖（adapter 仍是真的）。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    private long employeeId;
    private long itUserId;
    private long categoryId;

    @BeforeEach
    void prepareFixtures() {
        employeeId = insertUser("employee", EMPLOYEE);
        itUserId = insertUser("it", IT_SUPPORT);
        categoryId = insertCategory("超时");
    }

    @AfterEach
    void removeIntegrationTestData() {
        SecurityContextHolder.clearContext();
        jdbc.update("""
                DELETE notification
                FROM notification
                JOIN iam_user user ON user.id = notification.recipient_id
                WHERE user.username LIKE ?
                """, USERNAME_PREFIX + "%");
        jdbc.update("""
                DELETE notification
                FROM notification
                JOIN ticket ON ticket.id = notification.ticket_id
                JOIN ticket_category category ON category.id = ticket.category_id
                WHERE category.name LIKE ?
                """, CATEGORY_PREFIX + "%");
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

    // ---------- 自动完成 ----------

    /**
     * 待确认超时 → 自动完成：状态、`completion_method`、`ended_at`、版本与序号一次更新到位，
     * `COMPLETION` 留痕记为系统、`AUTO_CONFIRM_TIMEOUT`，并给提交人与最后负责人各发一条通知。
     */
    @Test
    void completeExpiredConfirmationClosesTheTicketAndPublishesTwoNotifications() {
        long ticketId = createWaitingForConfirmationTicket();
        moveConfirmationDeadlineIntoThePast(ticketId);
        long versionBefore = versionOf(ticketId);
        int recordSeqBefore = recordSeqOf(ticketId);

        int completed = ticketService.completeExpiredConfirmations(BATCH);

        assertThat(completed).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(ticketId)).isEqualTo(COMPLETED);
        assertThat(completionMethodOf(ticketId)).isEqualTo("AUTO_CONFIRM_TIMEOUT");
        assertThat(endedAtOf(ticketId)).isNotNull();
        assertThat(actionDeadlineOf(ticketId)).as("终态清空期限").isNull();
        assertThat(versionOf(ticketId)).isEqualTo(versionBefore + 1);
        assertThat(recordSeqOf(ticketId)).isEqualTo(recordSeqBefore + 1);

        var record = jdbc.queryForMap("""
                SELECT record_type, actor_type, actor_user_id, completion_method, from_status, to_status
                FROM ticket_record WHERE ticket_id = ? AND record_type = 'COMPLETION'
                """, ticketId);
        assertThat(record.get("actor_type")).isEqualTo("SYSTEM");
        assertThat(record.get("actor_user_id")).isNull();
        assertThat(record.get("completion_method")).isEqualTo("AUTO_CONFIRM_TIMEOUT");
        assertThat(record.get("from_status")).isEqualTo(WAITING_FOR_CONFIRMATION);
        assertThat(record.get("to_status")).isEqualTo(COMPLETED);

        List<Long> recipients = notificationRecipients(ticketId, "CONFIRMATION_TIMEOUT");
        assertThat(recipients)
                .as("提交人与最后负责人各一条（两者不同）")
                .containsExactlyInAnyOrder(employeeId, itUserId);
    }

    /**
     * 已被人工动作抢先 → 条件更新命中 0 行：不改状态、不写记录、不发通知。
     *
     * <p>造法：把期限改到过去让扫描看得见它，然后把版本 +1 模拟"另一个动作刚提交过"——
     * 条件更新的 {@code version = expectedVersion} 打不着，淘汰的是这次超时处置而不是工单。</p>
     */
    @Test
    void completeExpiredConfirmationSkipsSilentlyWhenTheVersionDrifted() {
        long ticketId = createWaitingForConfirmationTicket();
        moveConfirmationDeadlineIntoThePast(ticketId);
        int notificationsBefore = countNotifications(ticketId);
        int recordsBefore = countRecords(ticketId);

        //模拟扫描之后、更新之前有人抢先提交：版本前进，工单已不在"待确认"
        jdbc.update("UPDATE ticket SET status = 'PROCESSING', action_deadline_at = NULL, "
                + "version = version + 1 WHERE id = ?", ticketId);

        int completed = ticketService.completeExpiredConfirmations(BATCH);

        assertThat(completed).isZero();
        assertThat(statusOf(ticketId)).as("领先一步的动作结果不被覆盖").isEqualTo(PROCESSING);
        assertThat(countNotifications(ticketId)).as("没发生的超时不发通知").isEqualTo(notificationsBefore);
        assertThat(countRecords(ticketId)).as("没发生的超时不写留痕").isEqualTo(recordsBefore);
    }

    // ---------- 自动关闭 ----------

    /**
     * 待补充超时 → 自动关闭：`CLOSURE` 留痕带 `AUTO_SUPPLEMENT_TIMEOUT` +
     * `REQUESTER_NO_RESPONSE`，且把原补充截止时间写进记录的 `deadline_at`——
     * 条件更新成功后工单行的期限已清空，留痕是唯一能读到"当初要求什么时候补"的地方。
     */
    @Test
    void closeExpiredSupplementClosesTheTicketAndKeepsTheOriginalDeadlineInTheRecord() {
        long ticketId = createWaitingForRequesterTicket();
        assertThat(actionDeadlineOf(ticketId)).as("进入待补充时写入了期限").isNotNull();
        //先造超时条件，再读取"真实的原期限"——留痕记的是这一刻库里的值
        moveSupplementDeadlineIntoThePast(ticketId);
        LocalDateTime originalDeadline = actionDeadlineOf(ticketId);
        assertThat(originalDeadline).as("造数后期限已落到过去").isNotNull();

        int closed = ticketService.closeExpiredSupplements(BATCH);

        assertThat(closed).isGreaterThanOrEqualTo(1);
        assertThat(statusOf(ticketId)).isEqualTo(CLOSED);
        assertThat(closeMethodOf(ticketId)).isEqualTo("AUTO_SUPPLEMENT_TIMEOUT");
        assertThat(closeReasonOf(ticketId)).isEqualTo("REQUESTER_NO_RESPONSE");
        assertThat(actionDeadlineOf(ticketId)).isNull();
        assertThat(endedAtOf(ticketId)).isNotNull();
        assertThat(assigneeOf(ticketId)).as("自动关闭不改归属").isEqualTo(itUserId);

        var record = jdbc.queryForMap("""
                SELECT actor_type, actor_user_id, close_method, close_reason, deadline_at
                FROM ticket_record WHERE ticket_id = ? AND record_type = 'CLOSURE'
                """, ticketId);
        assertThat(record.get("actor_type")).isEqualTo("SYSTEM");
        assertThat(record.get("actor_user_id")).isNull();
        assertThat(record.get("close_method")).isEqualTo("AUTO_SUPPLEMENT_TIMEOUT");
        assertThat(record.get("close_reason")).isEqualTo("REQUESTER_NO_RESPONSE");
        assertThat(((LocalDateTime) record.get("deadline_at")))
                .as("原补充截止时间必须留在留痕里")
                .isEqualTo(originalDeadline);

        List<Long> recipients = notificationRecipients(ticketId, "SUPPLEMENT_TIMEOUT");
        assertThat(recipients).containsExactlyInAnyOrder(employeeId, itUserId);
    }

    /** 自动关闭的反向断言：版本漂移时静默跳过。 */
    @Test
    void closeExpiredSupplementSkipsSilentlyWhenTheVersionDrifted() {
        long ticketId = createWaitingForRequesterTicket();
        moveSupplementDeadlineIntoThePast(ticketId);
        int notificationsBefore = countNotifications(ticketId);

        jdbc.update("UPDATE ticket SET status = 'PROCESSING', action_deadline_at = NULL, "
                + "version = version + 1 WHERE id = ?", ticketId);

        assertThat(ticketService.closeExpiredSupplements(BATCH)).isZero();
        assertThat(statusOf(ticketId)).isEqualTo(PROCESSING);
        assertThat(countNotifications(ticketId)).isEqualTo(notificationsBefore);
    }

    // ---------- 到期前提醒 ----------

    /**
     * 期限进入提前量窗口（默认 1 天）时，确认提醒发给提交人一次。
     *
     * <p>连跑两轮：同一期限的去重键相同，{@code uk_notification_recipient_dedup} 让第二轮插不进去，
     * 因此通知数仍是 1——这就是"同一期限只提醒一次"的真库证据。</p>
     *
     * <p>注意不能拿方法返回值当"插了几行"的证据：它统计的是"重读命中并调用发布的工单数"，
     * 第二轮仍会命中并调用发布，只是 {@code ON DUPLICATE KEY UPDATE} 把插入挡在库里。
     * 真正证明去重生效的是 {@code notification} 表里的行数与去重键。</p>
     */
    @Test
    void dueSoonReminderIsPublishedOncePerDeadline() {
        long ticketId = createWaitingForConfirmationTicket();
        moveDeadlineIntoTheReminderWindow(ticketId);
        int publishedFirst = ticketService.publishDueSoonReminders(BATCH);
        ticketService.publishDueSoonReminders(BATCH);

        assertThat(publishedFirst).as("首轮命中并发出提醒").isGreaterThanOrEqualTo(1);

        List<Long> recipients = notificationRecipients(ticketId, "CONFIRMATION_DUE_SOON");
        assertThat(recipients)
                .as("确认提醒只发给提交人，两轮跑下来仍只有一条").containsExactly(employeeId);
        assertThat(dedupKeysOf(ticketId, "CONFIRMATION_DUE_SOON"))
                .as("键含期限毫秒值，且只有一条").hasSize(1)
                .allSatisfy(key -> assertThat(key).startsWith("CONFIRMATION_DUE_SOON:" + ticketId + ":"));
    }

    /**
     * 期限被改写 → 去重键变化 → 允许再提醒一次。
     * 这条与上一条合起来，正是"改了期限就该重新提醒"的完整语义。
     */
    @Test
    void reminderIsPublishedAgainAfterTheDeadlineIsRewritten() {
        long ticketId = createWaitingForConfirmationTicket();

        moveDeadlineIntoTheReminderWindow(ticketId);
        ticketService.publishDueSoonReminders(BATCH);

        //把确认期限改写成一个新的、仍在窗口内的值（键里的毫秒数不同）
        rewriteDeadlineIntoTheReminderWindow(ticketId, 6);

        int published = ticketService.publishDueSoonReminders(BATCH);

        assertThat(published).as("新期限产生新键，重新发一条").isGreaterThanOrEqualTo(1);
        assertThat(dedupKeysOf(ticketId, "CONFIRMATION_DUE_SOON"))
                .as("同一类别下两条不同期限的键并存").hasSize(2);
    }

    /** 撤销请求的提醒收件人是当前负责人，不是提交人——两段提醒的收件人分岔。 */
    @Test
    void cancelRequestReminderGoesToTheCurrentAssignee() {
        long ticketId = createProcessingTicketWithCancelRequest();
        rewriteCancelDeadlineIntoTheReminderWindow(ticketId, 6);

        int published = ticketService.publishDueSoonReminders(BATCH);

        assertThat(published).as("三类提醒至少发出撤销请求那条").isGreaterThanOrEqualTo(1);
        assertThat(notificationRecipients(ticketId, "CANCEL_REQUEST_DUE_SOON"))
                .as("撤销请求提醒发给负责人").containsExactly(itUserId);
    }

    // ---------- 提醒回收 ----------

    /**
     * 动作回收：撤销请求被批准后，负责人那条"还剩 N 小时"必须消失，
     * 而普通业务通知（CANCEL_REQUESTED）不受影响——回收只删带 {@code dedup_key} 的提醒行。
     */
    @Test
    void approvingCancelRemovesTheReminderButKeepsBusinessNotifications() {
        long ticketId = createProcessingTicketWithCancelRequest();
        rewriteCancelDeadlineIntoTheReminderWindow(ticketId, 6);
        ticketService.publishDueSoonReminders(BATCH);
        assertThat(notificationRecipients(ticketId, "CANCEL_REQUEST_DUE_SOON")).isNotEmpty();

        authenticateAs(itUserId);
        ticketService.approveCancel(ticketNoOf(ticketId),
                new com.flowdesk.ticket.application.command.ApproveCancelCommand(versionOf(ticketId)));

        assertThat(notificationRecipients(ticketId, "CANCEL_REQUEST_DUE_SOON"))
                .as("批准后撤销提醒被回收").isEmpty();
        assertThat(notificationRecipients(ticketId, "CANCEL_REQUESTED"))
                .as("请求时的业务通知是事实，不随回收消失").contains(itUserId);
    }

    /**
     * 撤销请求自然过期 → 超时任务兜底回收。
     *
     * <p>先把请求期限移到提前量窗口内并发出提醒，再把期限挪到过去（模拟自然过期），
     * 跑一轮超时任务：候选窗口内命中，加锁重读仍过期，于是删掉那条提醒。
     * 工单状态不变——到期不产生业务动作。</p>
     */
    @Test
    void recallExpiredCancelRemindersRemovesStaleReminderWithoutChangingStatus() {
        long ticketId = createProcessingTicketWithCancelRequest();
        rewriteCancelDeadlineIntoTheReminderWindow(ticketId, 6);
        ticketService.publishDueSoonReminders(BATCH);
        assertThat(notificationRecipients(ticketId, "CANCEL_REQUEST_DUE_SOON")).isNotEmpty();

        //把撤销请求期限挪到过去：请求自然过期但不产生业务动作
        jdbc.update("UPDATE ticket SET cancel_request_deadline_at = ? WHERE id = ?",
                LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(2), ticketId);

        int recalled = ticketService.recallExpiredCancelReminders(BATCH);

        assertThat(recalled).isGreaterThanOrEqualTo(1);
        assertThat(notificationRecipients(ticketId, "CANCEL_REQUEST_DUE_SOON"))
                .as("过期撤销请求的提醒被回收").isEmpty();
        assertThat(statusOf(ticketId)).as("到期不产生业务动作").isEqualTo(PROCESSING);
        assertThat(cancelRequestedAtOf(ticketId)).as("过期的请求三列保留").isNotNull();
    }

    /**
     * 重读防线的真库证据：候选已过期的行，在重读之前被"重新发起"（期限改写为新值），
     * 加锁重读判定不过 → 不删。避免误删新期限那条提醒。
     *
     * <p>造法：先让行落在候选窗口内（期限刚过期），随即把它改写成一个未来的有效期限——
     * 这正是"重新发起"在库里的样子；重读的 {@code cancel_request_deadline_at <= now} 不再成立。</p>
     */
    @Test
    void recallSkipsWhenTheRequestWasResubmittedBeforeTheLockedReread() {
        long ticketId = createProcessingTicketWithCancelRequest();
        rewriteCancelDeadlineIntoTheReminderWindow(ticketId, 6);
        ticketService.publishDueSoonReminders(BATCH);

        //候选落在回看窗口内（刚开始过期）……
        LocalDateTime justExpired = LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1);
        jdbc.update("UPDATE ticket SET cancel_request_deadline_at = ? WHERE id = ?",
                justExpired, ticketId);
        //……但同一行的期限随即被改写为未来的有效值 → 重读判定不过
        jdbc.update("UPDATE ticket SET cancel_request_deadline_at = ? WHERE id = ?",
                LocalDateTime.now(java.time.ZoneOffset.UTC).plusDays(2), ticketId);

        int recalled = ticketService.recallExpiredCancelReminders(BATCH);

        assertThat(recalled)
                .as("重读发现它已不再是过期请求，跳过回收").isZero();
        assertThat(notificationRecipients(ticketId, "CANCEL_REQUEST_DUE_SOON"))
                .as("新期限的提醒不应被误删").isNotEmpty();
    }

    // ---------- 造数辅助 ----------

    /** 建单 → 领取 → 提交解决结果：得到一张「待确认」的真实工单。 */
    private long createWaitingForConfirmationTicket() {
        authenticateAs(employeeId);
        TicketCreatedResult created = createTicket("待确认超时");
        authenticateAs(itUserId);
        TicketActionResult claimed = ticketService.claim(
                created.ticketNo(), new ClaimTicketCommand(created.version()));
        ticketService.submitResolution(created.ticketNo(),
                new com.flowdesk.ticket.application.command.SubmitResolutionCommand(
                        claimed.version(), "已恢复"));
        return ticketIdOf(created.ticketNo());
    }

    /** 建单 → 领取 → 请求补充：得到一张「待补充」的真实工单。 */
    private long createWaitingForRequesterTicket() {
        authenticateAs(employeeId);
        TicketCreatedResult created = createTicket("待补充超时");
        authenticateAs(itUserId);
        TicketActionResult claimed = ticketService.claim(
                created.ticketNo(), new ClaimTicketCommand(created.version()));
        ticketService.requestSupplement(created.ticketNo(),
                new RequestSupplementCommand(claimed.version(), "请补充打印机型号"));
        return ticketIdOf(created.ticketNo());
    }

    /** 建单 → 领取 → 发起撤销请求：得到一张有撤销请求、期限在窗口内的「处理中」工单。 */
    private long createProcessingTicketWithCancelRequest() {
        authenticateAs(employeeId);
        TicketCreatedResult created = createTicket("撤销请求提醒");
        authenticateAs(itUserId);
        ticketService.claim(created.ticketNo(), new ClaimTicketCommand(created.version()));
        authenticateAs(employeeId);
        ticketService.requestCancel(created.ticketNo(),
                new RequestCancelCommand(versionOf(ticketIdOf(created.ticketNo())), "问题已解决"));
        return ticketIdOf(created.ticketNo());
    }

    private TicketCreatedResult createTicket(String title) {
        return ticketService.create(new com.flowdesk.ticket.application.command.CreateTicketCommand(
                UUID.randomUUID().toString(), title, title + "-描述", categoryId, "MEDIUM",
                null, List.of()), List.of());
    }

    /**
     * 把期限挪到过去：条件更新的 {@code action_deadline_at <= now} 才会真的命中。
     *
     * <p>确认与补充共用工单行的 {@code action_deadline_at}，因此同一个造数动作即可覆盖两条链路；
     * 保留两个名字是为了让用例读起来仍然贴着各自的业务语境。</p>
     */
    private void moveConfirmationDeadlineIntoThePast(long ticketId) {
        moveActionDeadline(ticketId, LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(5));
    }

    private void moveSupplementDeadlineIntoThePast(long ticketId) {
        moveConfirmationDeadlineIntoThePast(ticketId);
    }

    /**
     * 把期限挪进提前量窗口 {@code (now, now + lead]}：默认提前量 1 天，取 now + 6h 稳落在窗口内。
     *
     * <p>新建工单的期限是默认的 7 天后，离提前量窗口还差得远，提醒扫描看不见它；
     * 必须改写期限才能让真库里的提醒路径真正命中。</p>
     */
    private void moveDeadlineIntoTheReminderWindow(long ticketId) {
        moveActionDeadline(ticketId, LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(6));
    }

    /** 把确认期限改写为 {@code now + hoursAhead}：用于制造"期限变了 → 去重键变了"。 */
    private void rewriteDeadlineIntoTheReminderWindow(long ticketId, int hoursAhead) {
        moveActionDeadline(ticketId, LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(hoursAhead));
    }

    /** 把撤销请求期限改写进提前量窗口：撤销提醒读的是 {@code cancel_request_deadline_at}。 */
    private void rewriteCancelDeadlineIntoTheReminderWindow(long ticketId, int hoursAhead) {
        jdbc.update("UPDATE ticket SET cancel_request_deadline_at = ? WHERE id = ?",
                LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(hoursAhead), ticketId);
    }

    private void moveActionDeadline(long ticketId, LocalDateTime deadline) {
        jdbc.update("UPDATE ticket SET action_deadline_at = ? WHERE id = ?", deadline, ticketId);
    }

    // ---------- 直查辅助 ----------

    private void authenticateAs(long userId) {
        List<SimpleGrantedAuthority> authorities = jdbc.queryForList("""
                SELECT DISTINCT permission.code
                FROM iam_user_role user_role
                JOIN iam_role_permission role_permission
                  ON role_permission.role_id = user_role.role_id
                JOIN iam_permission permission
                  ON permission.id = role_permission.permission_id
                WHERE user_role.user_id = ?
                ORDER BY permission.code
                """, String.class, userId).stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(userId, usernameOf(userId), "ittm-session"), null, authorities));
    }

    private long insertUser(String suffix, String... roleCodes) {
        String username = USERNAME_PREFIX + suffix;
        jdbc.update("""
                INSERT INTO iam_user (username, display_name, password, status,
                        created_at, updated_at, version)
                VALUES (?, ?, 'integration-test-hash', 'ENABLED',
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
                INSERT INTO ticket_category (name, status, sort_order, created_at, updated_at, version)
                VALUES (?, 'ENABLED', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0)
                """, name);
        return jdbc.queryForObject(
                "SELECT id FROM ticket_category WHERE name = ?", Long.class, name);
    }

    private long ticketIdOf(String ticketNo) {
        Long ticketId = jdbc.queryForObject(
                "SELECT id FROM ticket WHERE ticket_no = ?", Long.class, ticketNo);
        assertThat(ticketId).as("工单必须存在：" + ticketNo).isNotNull();
        return ticketId;
    }

    private String ticketNoOf(long ticketId) {
        return jdbc.queryForObject("SELECT ticket_no FROM ticket WHERE id = ?", String.class, ticketId);
    }

    private String usernameOf(long userId) {
        return jdbc.queryForObject("SELECT username FROM iam_user WHERE id = ?", String.class, userId);
    }

    private String statusOf(long ticketId) {
        return jdbc.queryForObject("SELECT status FROM ticket WHERE id = ?", String.class, ticketId);
    }

    private Long assigneeOf(long ticketId) {
        return jdbc.queryForObject("SELECT assignee_id FROM ticket WHERE id = ?", Long.class, ticketId);
    }

    private long versionOf(long ticketId) {
        Long version = jdbc.queryForObject("SELECT version FROM ticket WHERE id = ?", Long.class, ticketId);
        return version == null ? -1L : version;
    }

    private int recordSeqOf(long ticketId) {
        return jdbc.queryForObject("SELECT record_seq FROM ticket WHERE id = ?", Integer.class, ticketId);
    }

    private LocalDateTime actionDeadlineOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT action_deadline_at FROM ticket WHERE id = ?", LocalDateTime.class, ticketId);
    }

    private LocalDateTime endedAtOf(long ticketId) {
        return jdbc.queryForObject("SELECT ended_at FROM ticket WHERE id = ?", LocalDateTime.class, ticketId);
    }

    private LocalDateTime cancelRequestedAtOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT cancel_requested_at FROM ticket WHERE id = ?", LocalDateTime.class, ticketId);
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

    private int countNotifications(long ticketId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE ticket_id = ?", Integer.class, ticketId);
    }

    private int countRecords(long ticketId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_record WHERE ticket_id = ?", Integer.class, ticketId);
    }

    private List<Long> notificationRecipients(long ticketId, String category) {
        return jdbc.queryForList("""
                SELECT recipient_id FROM notification WHERE ticket_id = ? AND category = ?
                """, Long.class, ticketId, category);
    }

    private List<String> dedupKeysOf(long ticketId, String category) {
        return jdbc.queryForList("""
                SELECT dedup_key FROM notification WHERE ticket_id = ? AND category = ?
                """, String.class, ticketId, category);
    }
}
