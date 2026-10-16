package com.flowdesk.notification.application.service.impl;

import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.notification.application.command.PublishNotificationCommand;
import com.flowdesk.notification.application.query.NotificationQuery;
import com.flowdesk.notification.application.result.NotificationResult;
import com.flowdesk.notification.application.service.NotificationService;
import com.flowdesk.notification.domain.NotificationCategory;
import com.flowdesk.notification.mapper.NotificationMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 站内信服务的真实 MySQL 集成测试。
 *
 * <p>替身测不出来的部分都在这里：{@code notification} 的 CHECK 约束与两个限制删除外键、
 * 「本人范围」在真实 SQL 里的隔离（越权与不存在都查不到）、条件更新的真实影响行数（已读幂等），
 * 以及清理任务按 {@code read_at} 范围删除且永不碰未读。造数一律走 {@link JdbcTemplate}：
 * 收件人与工单行需要精确控制，走完整工单链路反而会被"必须存在的前置动作"绑住。</p>
 *
 * <p>与既有 IT 约定一致：{@code @ActiveProfiles("test")} + Testcontainers 临时库、
 * 不使用测试级 {@code @Transactional}（外层事务会掩盖服务自身的事务边界）、
 * 身份按 {@code JwtAuthenticationFilter} 的做法写入 {@code SecurityContextHolder}，
 * 每个用例结束后按前缀清理自己造的数据。</p>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
class NotificationServiceIT {

    private static final String MYSQL_IMAGE = "mysql:8.4.11";
    private static final String USERNAME_PREFIX = "itnt-";
    private static final String CATEGORY_PREFIX = "ITNT-";

    private static final String SUPPLEMENT_REQUESTED = "SUPPLEMENT_REQUESTED";
    private static final String CANCEL_APPROVED = "CANCEL_APPROVED";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("flowdesk_notification_it")
            .withUsername("flowdesk")
            .withPassword("flowdesk");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private NotificationService notificationService;

    /** 清理任务的真库行为直接对 Mapper 断言。 */
    @Autowired
    private NotificationMapper notificationMapper;

    /** 清理阈值与写入用同一个应用 UTC 时钟，避免进程与容器的时钟差。 */
    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    /** Redis 不属于本切片，用替身满足认证模块依赖（adapter 仍是真的）。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    @AfterEach
    void removeIntegrationTestData() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM notification WHERE recipient_id IN "
                + "(SELECT id FROM iam_user WHERE username LIKE ?)", USERNAME_PREFIX + "%");
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
                DELETE ticket
                FROM ticket
                JOIN ticket_category category ON category.id = ticket.category_id
                WHERE category.name LIKE ?
                """, CATEGORY_PREFIX + "%");
        jdbc.update("DELETE FROM ticket_category WHERE name LIKE ?", CATEGORY_PREFIX + "%");
        jdbc.update("DELETE FROM iam_user WHERE username LIKE ?", USERNAME_PREFIX + "%");
    }

    // ---------- 发布与本人范围 ----------

    @Test
    void publishedNotificationIsVisibleOnlyToItsRecipientWithEveryColumnPersisted() {
        long requesterId = insertUser("publisher");
        long otherId = insertUser("publisher-other");
        long categoryId = insertCategory("发布");
        String ticketNo = insertTicket(requesterId, categoryId, "站内信发布");
        long ticketId = ticketIdOf(ticketNo);

        notificationService.publish(new PublishNotificationCommand(
                requesterId, NotificationCategory.SUPPLEMENT_REQUESTED,
                "工单 " + ticketNo + " 需要你补充信息", "请在工单中查看需要补充的内容。",
                ticketId, ticketNo, null));

        authenticateAs(requesterId);

        PageResult<NotificationResult> page = notificationService.page(new NotificationQuery());
        assertThat(page.totalElements()).isEqualTo(1);
        NotificationResult item = page.items().getFirst();
        assertThat(item.id()).isPositive();
        assertThat(item.category()).isEqualTo(SUPPLEMENT_REQUESTED);
        assertThat(item.title()).isEqualTo("工单 " + ticketNo + " 需要你补充信息");
        assertThat(item.body()).isEqualTo("请在工单中查看需要补充的内容。");
        assertThat(item.ticketId()).isEqualTo(ticketId);
        assertThat(item.ticketNo()).isEqualTo(ticketNo);
        assertThat(item.readAt()).as("新通知未读").isNull();
        assertThat(item.createdAt()).as("创建时间来自应用 UTC 时钟").isNotNull();
        assertThat(notificationService.unreadCount().unreadCount()).isEqualTo(1L);

        // 另一个收件人的通知：本人列表与未读计数都看不到它
        notificationService.publish(new PublishNotificationCommand(
                otherId, NotificationCategory.CANCEL_APPROVED,
                "别人的标题", null, null, null, null));

        assertThat(notificationService.page(new NotificationQuery()).items())
                .as("越权不可见：本人列表只包含自己的通知").hasSize(1);
        assertThat(notificationService.unreadCount().unreadCount()).isEqualTo(1L);

        authenticateAs(otherId);
        PageResult<NotificationResult> otherPage = notificationService.page(new NotificationQuery());
        assertThat(otherPage.items()).hasSize(1);
        assertThat(otherPage.items().getFirst().title()).isEqualTo("别人的标题");
        assertThat(notificationService.unreadCount().unreadCount()).isEqualTo(1L);
    }

    @Test
    void unreadOnlyFilterReadsOnlyUnreadNotificationsOfTheCurrentUser() {
        long requesterId = insertUser("unread-filter");
        notificationService.publish(new PublishNotificationCommand(
                requesterId, NotificationCategory.CANCEL_APPROVED, "第一条", null, null, null, null));
        notificationService.publish(new PublishNotificationCommand(
                requesterId, NotificationCategory.CANCEL_REJECTED, "第二条", null, null, null, null));

        authenticateAs(requesterId);

        long firstId = notificationService.page(new NotificationQuery()).items().getFirst().id();
        notificationService.markRead(firstId);

        NotificationQuery unreadOnly = new NotificationQuery();
        unreadOnly.setUnreadOnly(Boolean.TRUE);
        PageResult<NotificationResult> unread = notificationService.page(unreadOnly);

        assertThat(unread.items()).as("只看未读时排除了已读那条").hasSize(1);
        assertThat(unread.items().getFirst().title()).isEqualTo("第一条");
        assertThat(unread.items().getFirst().readAt()).isNull();

        NotificationQuery all = new NotificationQuery();
        all.setUnreadOnly(Boolean.FALSE);
        assertThat(notificationService.page(all).items()).hasSize(2);
    }

    // ---------- 标记已读 ----------

    @Test
    void markReadSetsTheReadTimeAndIsIdempotentButRejectsForeignIds() {
        long requesterId = insertUser("mark-requester");
        long otherId = insertUser("mark-other");

        notificationService.publish(new PublishNotificationCommand(
                requesterId, NotificationCategory.SUPPLEMENT_REQUESTED, "本人通知", null, null, null, null));
        notificationService.publish(new PublishNotificationCommand(
                otherId, NotificationCategory.SUPPLEMENT_REQUESTED, "他人通知", null, null, null, null));

        long foreignId = notificationIdOf(otherId);

        authenticateAs(requesterId);
        long ownId = notificationService.page(new NotificationQuery()).items().getFirst().id();
        assertThat(notificationService.page(new NotificationQuery()).items().getFirst().readAt())
                .as("标记前未读").isNull();

        notificationService.markRead(ownId);

        assertThat(notificationService.page(new NotificationQuery()).items().getFirst().readAt())
                .as("标记后 read_at 非空").isNotNull();
        assertThat(notificationService.unreadCount().unreadCount()).isZero();

        // 已读重复标记幂等：不报错、不影响计数
        notificationService.markRead(ownId);
        assertThat(notificationService.unreadCount().unreadCount()).isZero();

        // 他人的通知：统一 404，且对方的行保持未读
        ApiException exception = catchApiError(() -> notificationService.markRead(foreignId));
        assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exception.code()).isEqualTo("NOTIFICATION_NOT_FOUND");
        assertThat(readAtOf(foreignId)).as("越权标记不能改变他人的行").isNull();
    }

    @Test
    void markAllReadClearsEveryUnreadNotificationAtOnce() {
        long requesterId = insertUser("mark-all");
        for (String title : List.of("甲", "乙", "丙")) {
            notificationService.publish(new PublishNotificationCommand(
                    requesterId, NotificationCategory.RESOLUTION_SUBMITTED, title, null, null, null, null));
        }

        authenticateAs(requesterId);
        assertThat(notificationService.unreadCount().unreadCount()).isEqualTo(3L);

        notificationService.markAllRead();

        assertThat(notificationService.unreadCount().unreadCount()).isZero();
        assertThat(notificationService.page(new NotificationQuery()).items())
                .allSatisfy(item -> assertThat(item.readAt()).isNotNull());

        // 没有未读时再调用同样幂等
        notificationService.markAllRead();
        assertThat(notificationService.unreadCount().unreadCount()).isZero();
    }

    // ---------- 未读计数只统计本人 ----------

    @Test
    void unreadCountOnlyCountsTheCurrentRecipientsRows() {
        long actorId = insertUser("count-actor");
        long otherId = insertUser("count-other");
        for (int index = 0; index < 2; index++) {
            notificationService.publish(new PublishNotificationCommand(
                    actorId, NotificationCategory.SUPPLEMENT_REQUESTED, "本人-" + index,
                    null, null, null, null));
        }
        notificationService.publish(new PublishNotificationCommand(
                otherId, NotificationCategory.SUPPLEMENT_REQUESTED, "他人", null, null, null, null));

        authenticateAs(actorId);
        assertThat(notificationService.unreadCount().unreadCount())
                .as("只统计本人未读：他人的那条不计入").isEqualTo(2L);

        authenticateAs(otherId);
        assertThat(notificationService.unreadCount().unreadCount()).isEqualTo(1L);
    }

    // ---------- 清理 ----------

    @Test
    void cleanupDeletesOnlyReadNotificationsPastTheCutoffAndKeepsUnreadOnes() {
        long recipientId = insertUser("cleanup");
        LocalDateTime oldReadAt = LocalDateTime.of(2020, 1, 1, 0, 0, 0);
        insertNotification(recipientId, oldReadAt, "很久以前已读");
        insertNotification(recipientId, null, "仍然是未读");

        assertThat(countNotifications(recipientId)).isEqualTo(2);

        LocalDateTime cutoff = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int deleted = notificationMapper.deleteReadBefore(cutoff, 500);

        assertThat(deleted).as("只删这一条已读且超过保留期的通知").isEqualTo(1);
        assertThat(titlesOf(recipientId)).as("已读那条被删除、未读那条保留")
                .containsExactly("仍然是未读");
    }

    // ---------- 辅助 ----------

    private long insertUser(String suffix) {
        String username = USERNAME_PREFIX + suffix;
        jdbc.update("""
                INSERT INTO iam_user (
                    username, display_name, password, status,
                    created_at, updated_at, version
                ) VALUES (?, ?, 'integration-test-hash', 'ENABLED',
                          CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0)
                """, username, username);
        return jdbc.queryForObject(
                "SELECT id FROM iam_user WHERE username = ?", Long.class, username);
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

    private String insertTicket(long requesterId, long categoryId, String title) {
        String ticketNo = "FD-20260115-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("""
                INSERT INTO ticket (
                    ticket_no, submission_key, requester_id, title, description,
                    category_id, priority, status, record_seq, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'MEDIUM', 'PENDING', 0, 0,
                          CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """, ticketNo, UUID.randomUUID().toString(), requesterId, title, title + "-描述",
                categoryId);
        return ticketNo;
    }

    private void insertNotification(long recipientId, LocalDateTime readAt, String title) {
        jdbc.update("""
                INSERT INTO notification (
                    recipient_id, category, title, body, ticket_id, ticket_no,
                    dedup_key, read_at, created_at
                ) VALUES (?, ?, ?, NULL, NULL, NULL, NULL, ?, CURRENT_TIMESTAMP(3))
                """, recipientId, SUPPLEMENT_REQUESTED, title, readAt);
    }

    /** 与 {@code JwtAuthenticationFilter} 一致：身份写入 {@code SecurityContextHolder}。 */
    private void authenticateAs(long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(userId, usernameOf(userId), "itnt-session"), null, List.of()));
    }

    private ApiException catchApiError(ThrowingCallable invocation) {
        ApiException exception = catchThrowableOfType(invocation, ApiException.class);
        assertThat(exception).isNotNull();
        return exception;
    }

    private long ticketIdOf(String ticketNo) {
        Long ticketId = jdbc.queryForObject(
                "SELECT id FROM ticket WHERE ticket_no = ?", Long.class, ticketNo);
        assertThat(ticketId).as("工单必须存在：" + ticketNo).isNotNull();
        return ticketId;
    }

    private long notificationIdOf(long recipientId) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM notification WHERE recipient_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, recipientId);
        assertThat(id).as("收件人必须有一条通知").isNotNull();
        return id;
    }

    private LocalDateTime readAtOf(long notificationId) {
        return jdbc.queryForObject(
                "SELECT read_at FROM notification WHERE id = ?", LocalDateTime.class, notificationId);
    }

    private int countNotifications(long recipientId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE recipient_id = ?",
                Integer.class, recipientId);
    }

    private List<String> titlesOf(long recipientId) {
        return jdbc.queryForList(
                "SELECT title FROM notification WHERE recipient_id = ? ORDER BY id",
                String.class, recipientId);
    }

    private String usernameOf(long userId) {
        return jdbc.queryForObject(
                "SELECT username FROM iam_user WHERE id = ?", String.class, userId);
    }
}
