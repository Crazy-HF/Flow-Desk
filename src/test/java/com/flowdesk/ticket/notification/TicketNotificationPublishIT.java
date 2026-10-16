package com.flowdesk.ticket.notification;

import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.ticket.application.command.ClaimTicketCommand;
import com.flowdesk.ticket.application.command.CreateTicketCommand;
import com.flowdesk.ticket.application.command.RequestCancelCommand;
import com.flowdesk.ticket.application.command.RequestSupplementCommand;
import com.flowdesk.ticket.application.result.TicketActionResult;
import com.flowdesk.ticket.application.result.TicketCreatedResult;
import com.flowdesk.ticket.application.service.TicketService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 工单动作与站内信「同事务发布」的真实 MySQL 集成测试。
 *
 * <p>替身测不出这条链路的两端：动作成功时通知真的落库（收件人、类型、编号逐列正确），
 * 动作因版本冲突失败时 {@code notification} 表一行都不多——后者是"同事务"的核心证据，
 * 只在真库上才成立：{@code TicketServiceImpl} 在条件更新成功之后才 {@code publish}，
 * 同一事务提交或回滚。</p>
 *
 * <p>独立于 {@code TicketServiceIT}：那条链路已有 100+ 用例、夹具前缀与清理逻辑固定，
 * 把通知断言塞进去会污染它的事务与清理边界；这里只造通知链路需要的最小前置
 * （建单 → 领取），并沿用相同的 Testcontainers、身份与清理约定。</p>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
class TicketNotificationPublishIT {

    private static final String MYSQL_IMAGE = "mysql:8.4.11";
    private static final String USERNAME_PREFIX = "itnp-";
    private static final String CATEGORY_PREFIX = "ITNP-";

    private static final String EMPLOYEE = "EMPLOYEE";
    private static final String IT_SUPPORT = "IT_SUPPORT";

    private static final String PROCESSING = "PROCESSING";
    private static final String WAITING_FOR_REQUESTER = "WAITING_FOR_REQUESTER";

    private static final String SUPPLEMENT_REQUESTED = "SUPPLEMENT_REQUESTED";
    private static final String CANCEL_REQUESTED = "CANCEL_REQUESTED";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("flowdesk_ticket_notification_it")
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
        categoryId = insertCategory("工单通知");
    }

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

    // ---------- 正常路径 ----------

    /**
     * 请求补充成功：通知落库一行，收件人是提交人，类型为补充请求，编号与工单一致。
     */
    @Test
    void requestSupplementPublishesOneNotificationToTheRequester() {
        String ticketNo = createProcessingTicket();
        long ticketId = ticketIdOf(ticketNo);

        assertThat(countNotifications(ticketId)).as("动作前没有通知").isZero();

        authenticateAs(itUserId);
        TicketActionResult requested = ticketService.requestSupplement(ticketNo,
                new RequestSupplementCommand(versionOf(ticketId), "请补充打印机型号与错误截图"));

        assertThat(requested.status()).isEqualTo(WAITING_FOR_REQUESTER);
        assertThat(countNotifications(ticketId)).as("动作成功后通知恰好多一条").isEqualTo(1);

        Map<String, Object> notification = onlyNotification(ticketId);
        assertThat(((Number) notification.get("recipient_id")).longValue())
                .as("提醒提交人补充信息").isEqualTo(employeeId);
        assertThat(notification.get("category")).isEqualTo(SUPPLEMENT_REQUESTED);
        assertThat(notification.get("ticket_no")).isEqualTo(ticketNo);
    }

    /**
     * 发起撤销请求成功：通知落库一行，收件人是当前负责人（而不是提交人自己）。
     */
    @Test
    void requestCancelPublishesOneNotificationToTheCurrentAssignee() {
        String ticketNo = createProcessingTicket();
        long ticketId = ticketIdOf(ticketNo);

        assertThat(countNotifications(ticketId)).isZero();

        authenticateAs(employeeId);
        TicketActionResult requested = ticketService.requestCancel(ticketNo,
                new RequestCancelCommand(versionOf(ticketId), "问题已自行解决"));

        assertThat(requested.status()).as("请求期间工单状态不变").isEqualTo(PROCESSING);
        assertThat(countNotifications(ticketId)).isEqualTo(1);

        Map<String, Object> notification = onlyNotification(ticketId);
        assertThat(((Number) notification.get("recipient_id")).longValue())
                .as("撤销请求通知当前负责人去批准").isEqualTo(itUserId);
        assertThat(notification.get("category")).isEqualTo(CANCEL_REQUESTED);
        assertThat(notification.get("ticket_no")).isEqualTo(ticketNo);
    }

    // ---------- 反向断言：动作失败则通知一并回滚 ----------

    @Test
    void staleVersionConflictLeavesNoNotificationBehind() {
        String ticketNo = createProcessingTicket();
        long ticketId = ticketIdOf(ticketNo);

        authenticateAs(itUserId);
        ApiException conflict = catchApiError(() -> ticketService.requestSupplement(ticketNo,
                new RequestSupplementCommand(versionOf(ticketId) + 5, "请补充打印机型号")));

        assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.code()).isEqualTo("TICKET_CONFLICT");
        assertThat(countNotifications(ticketId))
                .as("动作因版本冲突失败时，该动作的通知一行都不该落库").isZero();
        assertThat(statusOf(ticketId)).as("失败的动作不改工单状态").isEqualTo(PROCESSING);
    }

    // ---------- 辅助 ----------

    /** 建单 → 领取：得到一张「处理中」且负责人是 {@code itUserId} 的工单，返回其编号。 */
    private String createProcessingTicket() {
        authenticateAs(employeeId);
        TicketCreatedResult created = ticketService.create(new CreateTicketCommand(
                UUID.randomUUID().toString(), "站内信同事务", "站内信同事务-描述",
                categoryId, "MEDIUM", null, List.of()), List.of());

        authenticateAs(itUserId);
        ticketService.claim(created.ticketNo(), new ClaimTicketCommand(created.version()));
        return created.ticketNo();
    }

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
                """, String.class, userId).stream()
                .map(SimpleGrantedAuthority::new)
                .toList();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(userId, usernameOf(userId), "itnp-session"), null, authorities));
    }

    private ApiException catchApiError(ThrowingCallable invocation) {
        ApiException exception = catchThrowableOfType(invocation, ApiException.class);
        assertThat(exception).isNotNull();
        return exception;
    }

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

    private long ticketIdOf(String ticketNo) {
        Long ticketId = jdbc.queryForObject(
                "SELECT id FROM ticket WHERE ticket_no = ?", Long.class, ticketNo);
        assertThat(ticketId).as("工单必须存在：" + ticketNo).isNotNull();
        return ticketId;
    }

    private long versionOf(long ticketId) {
        Long version = jdbc.queryForObject(
                "SELECT version FROM ticket WHERE id = ?", Long.class, ticketId);
        assertThat(version).as("工单必须有版本").isNotNull();
        return version;
    }

    private String statusOf(long ticketId) {
        return jdbc.queryForObject(
                "SELECT status FROM ticket WHERE id = ?", String.class, ticketId);
    }

    private int countNotifications(long ticketId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE ticket_id = ?", Integer.class, ticketId);
    }

    private Map<String, Object> onlyNotification(long ticketId) {
        return jdbc.queryForMap("""
                SELECT recipient_id, category, ticket_no
                FROM notification
                WHERE ticket_id = ?
                """, ticketId);
    }

    private String usernameOf(long userId) {
        return jdbc.queryForObject(
                "SELECT username FROM iam_user WHERE id = ?", String.class, userId);
    }
}
