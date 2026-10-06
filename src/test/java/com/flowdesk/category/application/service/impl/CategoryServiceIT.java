package com.flowdesk.category.application.service.impl;

import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.category.application.command.CategoryStatusChangeCommand;
import com.flowdesk.category.application.command.CreateCategoryCommand;
import com.flowdesk.category.application.command.UpdateCategoryCommand;
import com.flowdesk.category.application.result.CategoryOptionResult;
import com.flowdesk.category.application.result.CategoryResult;
import com.flowdesk.category.application.service.CategoryService;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.ticket.application.command.CreateTicketCommand;
import com.flowdesk.ticket.application.result.TicketCreatedResult;
import com.flowdesk.ticket.application.service.TicketService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
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

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 分类服务的真实 MySQL 集成测试。
 *
 * <p>替换测不出来的部分都在这里：{@code uk_ticket_category_name} 唯一索引在
 * {@code utf8mb4_0900_ai_ci} 下的真实比较语义（首尾空白、大小写）、{@code selectByIdForUpdate}
 * 的 {@code FOR UPDATE} 锁内版本判定、8 路同版本并发改同一行时"锁内版本判定 + 条件更新"
 * 是否只剩一个赢家，以及删除被引用分类时 MySQL 1451 外键错误是否真的会被包装成
 * {@code 409/CATEGORY_IN_USE}。mock 让 {@code delete} 抛一个构造好的异常不能替代最后一条。</p>
 *
 * <p>与既有 IT 约定一致：{@code @ActiveProfiles("test")} + Testcontainers、不使用测试级
 * {@code @Transactional}（外层事务会掩盖服务自身的事务边界，{@code FOR UPDATE} 也会失效）、
 * 每个用例结束后按 {@code ITCAT-} / {@code itcat-} 前缀清理自己造的数据。</p>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
class CategoryServiceIT {

    private static final String MYSQL_IMAGE = "mysql:8.4.11";
    private static final String CATEGORY_PREFIX = "ITCAT-";
    private static final String USERNAME_PREFIX = "itcat-";
    private static final String ENABLED = "ENABLED";
    private static final String DISABLED = "DISABLED";
    private static final String EMPLOYEE = "EMPLOYEE";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("flowdesk_category_it")
            .withUsername("flowdesk")
            .withPassword("flowdesk");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private CategoryService categoryService;

    /** 停用分类不能建单这一条要跨模块验证，因此直接调用真实的工单创建服务。 */
    @Autowired
    private TicketService ticketService;

    @Autowired
    private JdbcTemplate jdbc;

    /** Redis 不属于本切片，用替身满足认证模块依赖（adapter 仍是真的）。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

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

    // ---------- 真实唯一索引 ----------

    @Test
    void createsNormalizedCategoryAndRejectsDuplicateNamesOnRealUniqueIndex() {
        CategoryResult created = categoryService.create(
                new CreateCategoryCommand("  " + CATEGORY_PREFIX + "重复  ", 10));

        assertThat(created.id()).isPositive();
        assertThat(created.name()).as("首尾空白在落库前被裁剪").isEqualTo(CATEGORY_PREFIX + "重复");
        assertThat(created.status()).isEqualTo(ENABLED);
        assertThat(created.sortOrder()).isEqualTo(10);
        assertThat(created.version()).isZero();
        assertThat(created.createdAt().getOffset()).isEqualTo(java.time.ZoneOffset.UTC);

        // 只差首尾空白：唯一索引看到的是同一个值
        assertApiError(
                () -> categoryService.create(new CreateCategoryCommand("\t" + CATEGORY_PREFIX + "重复", 11)),
                HttpStatus.CONFLICT,
                "CATEGORY_NAME_CONFLICT");
        // utf8mb4_0900_ai_ci：唯一索引对大小写不敏感，服务端预检查必须与它一致
        categoryService.create(new CreateCategoryCommand(CATEGORY_PREFIX + "Case", 12));
        assertApiError(
                () -> categoryService.create(new CreateCategoryCommand("itcat-case", 13)),
                HttpStatus.CONFLICT,
                "CATEGORY_NAME_CONFLICT");

        assertThat(countByName(CATEGORY_PREFIX + "重复")).isEqualTo(1);
        assertThat(countByName(CATEGORY_PREFIX + "Case")).isEqualTo(1);
    }

    // ---------- FOR UPDATE 锁内版本判定 ----------

    @Test
    void updateInsideLockRejectsStaleVersionWithoutChangingRow() {
        CategoryResult created = categoryService.create(
                new CreateCategoryCommand(CATEGORY_PREFIX + "更新", 1));

        assertApiError(
                () -> categoryService.update(created.id(), new UpdateCategoryCommand(
                        CATEGORY_PREFIX + "更新-越界", 2, created.version() + 1)),
                HttpStatus.CONFLICT,
                "CATEGORY_CONFLICT");

        assertThat(nameOf(created.id())).as("版本过期时不得产生部分写入")
                .isEqualTo(CATEGORY_PREFIX + "更新");
        assertThat(versionOf(created.id())).isZero();

        CategoryResult updated = categoryService.update(created.id(), new UpdateCategoryCommand(
                "  " + CATEGORY_PREFIX + "更新-新名  ", 5, created.version()));

        assertThat(updated.name()).isEqualTo(CATEGORY_PREFIX + "更新-新名");
        assertThat(updated.sortOrder()).isEqualTo(5);
        assertThat(updated.version()).isEqualTo(created.version() + 1);
        assertThat(nameOf(created.id())).isEqualTo(CATEGORY_PREFIX + "更新-新名");
        assertThat(sortOrderOf(created.id())).isEqualTo(5);
        assertThat(versionOf(created.id())).isEqualTo(1);

        // 已经用掉的版本不能再用一次
        assertApiError(
                () -> categoryService.update(created.id(), new UpdateCategoryCommand(
                        CATEGORY_PREFIX + "更新-第二次", 6, created.version())),
                HttpStatus.CONFLICT,
                "CATEGORY_CONFLICT");
        assertThat(versionOf(created.id())).isEqualTo(1);
    }

    /**
     * 真并发：8 个线程带着同一个版本改同一张分类。
     *
     * <p>锁协议是"先 {@code SELECT ... FOR UPDATE} 再判版本"，因此 8 条事务在分类行上串行：
     * 第一个拿到锁的通过判定并提交，其余 7 个读到的是已被推进的版本，只能得到
     * {@code 409/CATEGORY_CONFLICT}。终态版本只能 +1。</p>
     */
    @Test
    void eightWayConcurrentUpdateOfSameVersionKeepsExactlyOneWinner() throws Exception {
        int threads = 8;
        CategoryResult created = categoryService.create(
                new CreateCategoryCommand(CATEGORY_PREFIX + "并发八路", 3));

        CyclicBarrier barrier = new CyclicBarrier(threads);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            int threadIndex = index;
            tasks.add(() -> {
                barrier.await(30, TimeUnit.SECONDS);
                return categoryService.update(created.id(), new UpdateCategoryCommand(
                        CATEGORY_PREFIX + "并发八路-" + threadIndex, threadIndex, created.version()));
            });
        }

        List<Object> results = runConcurrently(tasks);

        List<CategoryResult> winners = results.stream()
                .filter(CategoryResult.class::isInstance)
                .map(CategoryResult.class::cast)
                .toList();
        assertThat(winners).as("同版本并发改名只能有一个赢家").hasSize(1);

        assertThat(results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast)
                .toList())
                .as("其余 7 个调用必须是版本冲突，而不是名称冲突或 500")
                .hasSize(threads - 1)
                .allSatisfy(exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("CATEGORY_CONFLICT");
                });

        assertThat(versionOf(created.id())).as("终态版本只 +1").isEqualTo(1);
        assertThat(nameOf(created.id())).isEqualTo(winners.getFirst().name());
        assertThat(sortOrderOf(created.id())).isEqualTo(winners.getFirst().sortOrder());
    }

    // ---------- 启停与 options ----------

    @Test
    void disableRemovesFromOptionsAndEnableRestoresItIdempotently() {
        CategoryResult created = categoryService.create(
                new CreateCategoryCommand(CATEGORY_PREFIX + "启停", 0));
        assertThat(optionIds()).contains(created.id());
        assertThat(categoryService.isEnabled(created.id())).isTrue();

        CategoryResult disabled = categoryService.disable(
                created.id(), new CategoryStatusChangeCommand(created.version()));

        assertThat(disabled.status()).isEqualTo(DISABLED);
        assertThat(disabled.version()).isEqualTo(created.version() + 1);
        assertThat(categoryService.options()).extracting(CategoryOptionResult::id)
                .as("停用后不能再出现在建单分类选项中")
                .doesNotContain(created.id());
        assertThat(categoryService.isEnabled(created.id())).isFalse();
        assertThat(statusOf(created.id())).isEqualTo(DISABLED);

        CategoryResult disabledAgain = categoryService.disable(
                created.id(), new CategoryStatusChangeCommand(disabled.version()));
        assertThat(disabledAgain.status()).isEqualTo(DISABLED);
        assertThat(disabledAgain.version()).as("已经是目标状态时幂等成功且不推进版本")
                .isEqualTo(disabled.version());
        assertThat(versionOf(created.id())).isEqualTo(disabled.version());

        CategoryResult enabled = categoryService.enable(
                created.id(), new CategoryStatusChangeCommand(disabledAgain.version()));
        assertThat(enabled.status()).isEqualTo(ENABLED);
        assertThat(enabled.version()).isEqualTo(disabled.version() + 1);
        assertThat(categoryService.isEnabled(created.id())).isTrue();
        assertThat(optionIds()).contains(created.id());

        CategoryResult enabledAgain = categoryService.enable(
                created.id(), new CategoryStatusChangeCommand(enabled.version()));
        assertThat(enabledAgain.status()).isEqualTo(ENABLED);
        assertThat(enabledAgain.version()).isEqualTo(enabled.version());
        assertThat(versionOf(created.id())).isEqualTo(enabled.version());

        // 停用用的还是启用前的版本：锁内判定必须拒绝
        assertApiError(
                () -> categoryService.disable(created.id(),
                        new CategoryStatusChangeCommand(created.version())),
                HttpStatus.CONFLICT,
                "CATEGORY_CONFLICT");
        assertThat(statusOf(created.id())).isEqualTo(ENABLED);
    }

    // ---------- 删除与真实外键 ----------

    @Test
    void deleteRejectsReferencedCategoryThroughRealForeignKeyAndAllowsUnreferencedOne() {
        CategoryResult referenced = categoryService.create(
                new CreateCategoryCommand(CATEGORY_PREFIX + "被引用", 0));
        long requesterId = insertUserWithRole("delete-requester", EMPLOYEE);
        long ticketId = insertTicket(requesterId, referenced.id(), "FD-20260101-901");

        // 真实外键的直接证据：绕开服务裸删父行，MySQL 必须报 1451
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ticket_category WHERE id = ?", referenced.id()))
                .satisfies(exception -> assertThat(mySqlErrorCode(exception))
                        .as("父行仍被 ticket.category_id 引用")
                        .isEqualTo(1451));

        assertApiError(
                () -> categoryService.delete(referenced.id(), referenced.version()),
                HttpStatus.CONFLICT,
                "CATEGORY_IN_USE");

        assertThat(countCategory(referenced.id())).as("删除失败时分类必须还在")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT category_id FROM ticket WHERE id = ?", Long.class, ticketId))
                .isEqualTo(referenced.id());

        CategoryResult unreferenced = categoryService.create(
                new CreateCategoryCommand(CATEGORY_PREFIX + "未引用", 1));
        assertApiError(
                () -> categoryService.delete(unreferenced.id(), unreferenced.version() + 1),
                HttpStatus.CONFLICT,
                "CATEGORY_CONFLICT");

        categoryService.delete(unreferenced.id(), unreferenced.version());

        assertThat(countCategory(unreferenced.id())).isZero();
    }

    // ---------- 与工单创建的联动 ----------

    /**
     * 停用分类不能用，且**不消耗当日编号**：分类校验发生在每日序号自增之前，
     * 因此被拒绝的创建不能在任何业务日留下痕迹。
     */
    @Test
    void disabledCategoryCannotCreateTicketAndDoesNotConsumeDailySequence() {
        long requesterId = insertUserWithRole("disabled-category", EMPLOYEE);
        authenticateAs(requesterId, USERNAME_PREFIX + "disabled-category");

        CategoryResult category = categoryService.create(
                new CreateCategoryCommand(CATEGORY_PREFIX + "已停用", 0));
        CategoryResult disabled = categoryService.disable(
                category.id(), new CategoryStatusChangeCommand(category.version()));

        Map<LocalDate, Long> before = dailySequence();

        assertApiError(
                () -> ticketService.create(new CreateTicketCommand(
                        UUID.randomUUID().toString(), "停用分类建单", "应当被拒绝",
                        category.id(), "MEDIUM")),
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED");

        assertThat(dailySequence()).as("被拒绝的创建不消耗当日编号").isEqualTo(before);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket WHERE requester_id = ?", Integer.class, requesterId))
                .isZero();

        // 同一个分类重新启用后可以建单：证明刚才挡住它的是"停用"，不是别的校验
        categoryService.enable(category.id(), new CategoryStatusChangeCommand(disabled.version()));
        TicketCreatedResult created = ticketService.create(new CreateTicketCommand(
                UUID.randomUUID().toString(), "启用后建单", "应当创建成功",
                category.id(), "MEDIUM"));

        assertThat(created.status()).isEqualTo("PENDING");
        assertThat(created.ticketNo()).startsWith("FD-");
        assertThat(countByName(CATEGORY_PREFIX + "已停用")).isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private long insertUserWithRole(String suffix, String roleCode) {
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
        jdbc.update("""
                INSERT INTO iam_user_role (user_id, role_id, granted_by, granted_at)
                SELECT ?, id, NULL, CURRENT_TIMESTAMP(3) FROM iam_role WHERE code = ?
                """, userId, roleCode);
        return userId;
    }

    /** 与 {@code JwtAuthenticationFilter} 一致：权限取自会话快照，由角色授权推导。 */
    private void authenticateAs(long userId, String username) {
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
                        new AuthPrincipal(userId, username, "itcat-session"), null, authorities));
    }

    private long insertTicket(long requesterId, long categoryId, String ticketNo) {
        jdbc.update("""
                INSERT INTO ticket (
                    ticket_no, submission_key, requester_id, title, description,
                    category_id, priority, status, record_seq, version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 'MEDIUM', 'PENDING', 0, 0,
                          CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                """, ticketNo, UUID.randomUUID().toString(), requesterId,
                "分类引用验证", "分类引用验证描述", categoryId);
        return jdbc.queryForObject(
                "SELECT id FROM ticket WHERE ticket_no = ?", Long.class, ticketNo);
    }

    private List<Long> optionIds() {
        return categoryService.options().stream().map(CategoryOptionResult::id).toList();
    }

    private Map<LocalDate, Long> dailySequence() {
        return jdbc.query("SELECT business_date, current_value FROM ticket_daily_sequence",
                resultSet -> {
                    Map<LocalDate, Long> values = new TreeMap<>();
                    while (resultSet.next()) {
                        values.put(resultSet.getDate("business_date").toLocalDate(),
                                resultSet.getLong("current_value"));
                    }
                    return values;
                });
    }

    private int countCategory(long categoryId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_category WHERE id = ?", Integer.class, categoryId);
    }

    private int countByName(String name) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ticket_category WHERE name = ?", Integer.class, name);
    }

    private String nameOf(long categoryId) {
        return jdbc.queryForObject(
                "SELECT name FROM ticket_category WHERE id = ?", String.class, categoryId);
    }

    private String statusOf(long categoryId) {
        return jdbc.queryForObject(
                "SELECT status FROM ticket_category WHERE id = ?", String.class, categoryId);
    }

    private int sortOrderOf(long categoryId) {
        return jdbc.queryForObject(
                "SELECT sort_order FROM ticket_category WHERE id = ?", Integer.class, categoryId);
    }

    private long versionOf(long categoryId) {
        Long version = jdbc.queryForObject(
                "SELECT version FROM ticket_category WHERE id = ?", Long.class, categoryId);
        return version == null ? -1L : version;
    }

    /** 沿 cause 链找出 MySQL 错误码；不是 SQLException 时返回 {@code -1}。 */
    private static int mySqlErrorCode(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getErrorCode();
            }
        }
        return -1;
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

    private void assertApiError(
            ThrowingCallable invocation, HttpStatus expectedStatus, String expectedCode) {
        ApiException exception = catchThrowableOfType(invocation, ApiException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.status()).isEqualTo(expectedStatus);
        assertThat(exception.code()).isEqualTo(expectedCode);
    }
}
