package com.flowdesk.iam.application.service.impl;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.application.command.CreateUserCommand;
import com.flowdesk.iam.application.command.ReplaceUserRolesCommand;
import com.flowdesk.iam.application.command.ResetUserPasswordCommand;
import com.flowdesk.iam.application.command.UpdateUserCommand;
import com.flowdesk.iam.application.command.UserStatusChangeCommand;
import com.flowdesk.iam.application.query.UserQuery;
import com.flowdesk.iam.application.result.UserResult;
import com.flowdesk.iam.application.service.IamUserService;
import com.flowdesk.iam.domain.IamUserStatus;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.verify;

/**
 * 用户服务的真实 MySQL 集成测试。
 *
 * <p>这里覆盖替身测不出来的部分：注解 SQL（{@code selectUserPage} 的动态条件与分页）、
 * {@code FOR UPDATE} 锁顺序、"最后启用管理员"在真实关系表上的计数、以及版本条件更新的实际影响行数。
 * 不使用测试级 {@code @Transactional}，以免外层事务掩盖服务自身的事务边界。</p>
 *
 * <p>Redis 不属于本切片：会话撤销经 {@code AuthSessionRepository} 替身观察（adapter 仍是真的）。</p>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
class IamUserServiceIT {

    private static final String MYSQL_IMAGE = "mysql:8.4.11";
    private static final String USERNAME_PREFIX = "user-it-";
    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    private static final String EMPLOYEE = "EMPLOYEE";
    private static final String IT_SUPPORT = "IT_SUPPORT";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("flowdesk_user_it")
            .withUsername("flowdesk")
            .withPassword("flowdesk");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private IamUserService userService;

    @Autowired
    private JdbcTemplate jdbc;

    /** Redis 不属于本切片，用替身承接会话撤销并留下可断言的调用记录。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    private long operatorId;

    /** 新用户与角色授权都需要真实操作人身份：{@code granted_by} 不允许为空。 */
    @BeforeEach
    void prepareOperator() {
        operatorId = insertUser("user-it-operator");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(operatorId, "user-it-operator", "session"),
                        null,
                        List.of()));
    }

    @AfterEach
    void removeIntegrationTestData() {
        SecurityContextHolder.clearContext();
        jdbc.update("""
                DELETE user_role
                FROM iam_user_role user_role
                JOIN iam_user user ON user.id = user_role.user_id
                WHERE user.username LIKE 'user-it-%'
                """);
        jdbc.update("DELETE FROM iam_user WHERE username LIKE 'user-it-%'");
    }

    // ---------- 创建 ----------

    @Test
    void createsUserWithZeroRolesAndStartsVersionAtZero() {
        UserResult created = userService.create(
                new CreateUserCommand("user-it-zero", "零角色用户", "Password#2026", null));

        assertThat(created.id()).isPositive();
        assertThat(created.username()).isEqualTo("user-it-zero");
        assertThat(created.displayName()).isEqualTo("零角色用户");
        assertThat(created.status()).isEqualTo(IamUserStatus.ENABLED);
        assertThat(created.version()).isZero();
        assertThat(created.roles()).isEmpty();
        assertThat(created.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_user WHERE username = 'user-it-zero'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_user_role user_role
                JOIN iam_user user ON user.id = user_role.user_id
                WHERE user.username = 'user-it-zero'
                """, Integer.class)).isZero();
        // 密码只以摘要落库，明文不进数据库
        assertThat(jdbc.queryForObject(
                "SELECT password FROM iam_user WHERE username = 'user-it-zero'", String.class))
                .isNotEqualTo("Password#2026")
                .startsWith("$");
    }

    @Test
    void createsUserWithRolesAndWritesGrantAuditColumns() {
        long employeeRole = roleId(EMPLOYEE);
        long supportRole = roleId(IT_SUPPORT);

        UserResult created = userService.create(new CreateUserCommand(
                "user-it-roles", "有角色用户", "Password#2026", List.of(supportRole, employeeRole)));

        assertThat(created.roles()).extracting(summary -> summary.id())
                .containsExactly(employeeRole, supportRole);
        for (long roleId : List.of(employeeRole, supportRole)) {
            assertThat(jdbc.queryForObject("""
                    SELECT granted_by FROM iam_user_role
                    WHERE user_id = ? AND role_id = ?
                    """, Long.class, created.id(), roleId))
                    .as("新授权必须写入授权人")
                    .isEqualTo(operatorId);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM iam_user_role
                    WHERE user_id = ? AND role_id = ? AND granted_at IS NOT NULL
                    """, Integer.class, created.id(), roleId))
                    .as("新授权必须写入授权时间")
                    .isEqualTo(1);
        }
    }

    @Test
    void rejectsDuplicateUsernameOnRealUniqueIndex() {
        userService.create(new CreateUserCommand("user-it-dup", "原用户", "Password#2026", null));

        assertApiError(
                () -> userService.create(
                        new CreateUserCommand("user-it-dup", "重复用户", "Password#2026", null)),
                HttpStatus.CONFLICT,
                "USERNAME_CONFLICT");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_user WHERE username = 'user-it-dup'", Integer.class))
                .isEqualTo(1);
    }

    /** 任一角色不存在时整单回滚：不能在库里留下没有授权的半成品用户。 */
    @Test
    void rollsBackCreatedUserWhenAnyRequestedRoleIsMissing() {
        long employeeRole = roleId(EMPLOYEE);

        assertApiError(
                () -> userService.create(new CreateUserCommand(
                        "user-it-rollback", "回滚用户", "Password#2026",
                        List.of(employeeRole, Long.MAX_VALUE))),
                HttpStatus.NOT_FOUND,
                "ROLE_NOT_FOUND");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_user WHERE username = 'user-it-rollback'", Integer.class))
                .as("角色校验失败时用户插入必须随事务回滚")
                .isZero();
    }

    /** 并发创建同一登录名时预检查必然同时通过，必须由唯一索引兜底并落回同一错误码。 */
    @Test
    void concurrentCreationOfSameUsernameKeepsExactlyOneUser() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> createAfterBarrier(barrier, "user-it-race"),
                () -> createAfterBarrier(barrier, "user-it-race")));

        assertThat(results.stream().filter(UserResult.class::isInstance)).hasSize(1);
        assertThat(results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast))
                .singleElement()
                .satisfies(exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("USERNAME_CONFLICT");
                });

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_user WHERE username = 'user-it-race'", Integer.class))
                .isEqualTo(1);
    }

    // ---------- 分页与详情 ----------

    @Test
    void pagesRealRowsFilteringByKeywordStatusAndRole() {
        long employeeRole = roleId(EMPLOYEE);
        UserResult first = userService.create(new CreateUserCommand(
                "user-it-page-a", "分页用户", "Password#2026", List.of(employeeRole)));
        userService.create(new CreateUserCommand(
                "user-it-page-b", "另一用户", "Password#2026", null));
        UserResult disabled = userService.create(new CreateUserCommand(
                "user-it-page-c", "停用用户", "Password#2026", List.of(employeeRole)));
        userService.disable(disabled.id(), new UserStatusChangeCommand(disabled.version()));

        UserQuery keywordQuery = new UserQuery();
        keywordQuery.setKeyword("分页用户");
        PageResult<UserResult> byKeyword = userService.page(keywordQuery);
        assertThat(byKeyword.items()).extracting(UserResult::id).containsExactly(first.id());

        UserQuery usernameQuery = new UserQuery();
        usernameQuery.setKeyword("page-b");
        assertThat(userService.page(usernameQuery).items())
                .extracting(UserResult::username)
                .containsExactly("user-it-page-b");

        UserQuery statusQuery = new UserQuery();
        statusQuery.setKeyword(USERNAME_PREFIX + "page");
        statusQuery.setStatus(IamUserStatus.DISABLED);
        assertThat(userService.page(statusQuery).items())
                .extracting(UserResult::id)
                .containsExactly(disabled.id());

        UserQuery roleQuery = new UserQuery();
        roleQuery.setKeyword(USERNAME_PREFIX + "page");
        roleQuery.setRoleId(employeeRole);
        assertThat(userService.page(roleQuery).items())
                .extracting(UserResult::id)
                .containsExactly(first.id(), disabled.id());

        // 列表项必须带上各自的角色摘要，零角色用户返回空列表而不是 null
        assertThat(userService.page(roleQuery).items().getFirst().roles())
                .extracting(summary -> summary.code())
                .containsExactly(EMPLOYEE);
    }

    @Test
    void pageAppliesPagingTotalsAndStableOrderingOnRealRows() {
        for (String suffix : List.of("a", "b", "c")) {
            userService.create(new CreateUserCommand(
                    USERNAME_PREFIX + "order-" + suffix, "同名排序用户", "Password#2026", null));
        }

        UserQuery query = new UserQuery();
        query.setKeyword(USERNAME_PREFIX + "order");
        query.setOrderBy("display_name");
        query.setOrderDirection("asc");
        query.setPageNo(1);
        query.setPageSize(2);

        PageResult<UserResult> firstPage = userService.page(query);

        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.totalPages()).isEqualTo(2);
        assertThat(firstPage.items()).hasSize(2);
        // display_name 全部相同，稳定排序由追加的 id asc 保证
        List<Long> ids = firstPage.items().stream().map(UserResult::id).sorted().toList();
        assertThat(ids).isSorted();

        query.setPageNo(2);
        assertThat(userService.page(query).items()).hasSize(1);
    }

    @Test
    void pageRejectsOrderFieldOutsideWhitelist() {
        UserQuery query = new UserQuery();
        query.setOrderBy("password");

        assertApiError(() -> userService.page(query), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");
    }

    @Test
    void getByIdReturnsNotFoundForUnknownUser() {
        assertApiError(() -> userService.getById(Long.MAX_VALUE),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    // ---------- 修改资料与版本条件更新 ----------

    @Test
    void updateBumpsVersionAndRejectsStaleVersion() {
        UserResult created = userService.create(
                new CreateUserCommand("user-it-update", "旧名称", "Password#2026", null));

        UserResult updated = userService.update(
                created.id(), new UpdateUserCommand("新名称", created.version()));

        assertThat(updated.displayName()).isEqualTo("新名称");
        assertThat(updated.version()).isEqualTo(created.version() + 1);

        // 旧版本再次提交必然影响 0 行：返回值必须是冲突而不是静默成功
        assertApiError(
                () -> userService.update(created.id(), new UpdateUserCommand("再次修改", created.version())),
                HttpStatus.CONFLICT,
                "USER_CONFLICT");

        assertThat(jdbc.queryForObject(
                "SELECT display_name FROM iam_user WHERE id = ?", String.class, created.id()))
                .isEqualTo("新名称");
    }

    @Test
    void updateReturnsNotFoundForUnknownUser() {
        assertApiError(
                () -> userService.update(Long.MAX_VALUE, new UpdateUserCommand("新名称", 0L)),
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND");
    }

    // ---------- 启用与停用 ----------

    @Test
    void disableThenEnableTogglesStatusAndRevokesSessionsOnRealRows() {
        UserResult created = userService.create(
                new CreateUserCommand("user-it-toggle", "启停用户", "Password#2026", null));

        UserResult disabled = userService.disable(
                created.id(), new UserStatusChangeCommand(created.version()));

        assertThat(disabled.status()).isEqualTo(IamUserStatus.DISABLED);
        assertThat(disabled.version()).isEqualTo(created.version() + 1);
        verify(authSessionRepository).revokeAll(created.id());

        // 已经停用：幂等成功且不动版本
        UserResult again = userService.disable(
                created.id(), new UserStatusChangeCommand(disabled.version()));
        assertThat(again.status()).isEqualTo(IamUserStatus.DISABLED);
        assertThat(again.version()).isEqualTo(disabled.version());

        UserResult enabled = userService.enable(
                created.id(), new UserStatusChangeCommand(again.version()));
        assertThat(enabled.status()).isEqualTo(IamUserStatus.ENABLED);
        assertThat(enabled.version()).isEqualTo(again.version() + 1);
    }

    @Test
    void disableRejectsStaleVersionWithoutChangingStatus() {
        UserResult created = userService.create(
                new CreateUserCommand("user-it-stale", "版本用户", "Password#2026", null));

        assertApiError(
                () -> userService.disable(created.id(), new UserStatusChangeCommand(created.version() + 1)),
                HttpStatus.CONFLICT,
                "USER_CONFLICT");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM iam_user WHERE id = ?", String.class, created.id()))
                .isEqualTo("ENABLED");
    }

    @Test
    void disableProtectsLastEnabledAdministrator() {
        long adminRole = roleId(SYSTEM_ADMIN);
        UserResult admin = userService.create(new CreateUserCommand(
                "user-it-last-admin", "唯一管理员", "Password#2026", List.of(adminRole)));

        assertApiError(
                () -> userService.disable(admin.id(), new UserStatusChangeCommand(admin.version())),
                HttpStatus.CONFLICT,
                "LAST_ADMIN_PROTECTED");

        assertThat(jdbc.queryForObject(
                "SELECT status FROM iam_user WHERE id = ?", String.class, admin.id()))
                .as("保护生效时不允许留下停用后的最后一个管理员")
                .isEqualTo("ENABLED");
    }

    @Test
    void disableAllowsAdministratorWhenAnotherEnabledAdministratorRemains() {
        long adminRole = roleId(SYSTEM_ADMIN);
        UserResult first = userService.create(new CreateUserCommand(
                "user-it-admin-a", "管理员甲", "Password#2026", List.of(adminRole)));
        userService.create(new CreateUserCommand(
                "user-it-admin-b", "管理员乙", "Password#2026", List.of(adminRole)));

        UserResult disabled = userService.disable(
                first.id(), new UserStatusChangeCommand(first.version()));

        assertThat(disabled.status()).isEqualTo(IamUserStatus.DISABLED);
        assertThat(enabledHolders(adminRole)).isEqualTo(1);
    }

    /**
     * 真实并发：两个启用管理员同时停用各自账号，"最后启用管理员"必须只放行一个。
     * 锁协议是"先锁 {@code SYSTEM_ADMIN} 角色行，再锁目标用户"，因此两者在这里被串行化。
     */
    @Test
    void concurrentDisableOfTwoEnabledAdministratorsKeepsExactlyOneEnabled() throws Exception {
        long adminRole = roleId(SYSTEM_ADMIN);
        UserResult first = userService.create(new CreateUserCommand(
                "user-it-conc-a", "并发管理员甲", "Password#2026", List.of(adminRole)));
        UserResult second = userService.create(new CreateUserCommand(
                "user-it-conc-b", "并发管理员乙", "Password#2026", List.of(adminRole)));
        assertThat(enabledHolders(adminRole)).isEqualTo(2);

        CyclicBarrier barrier = new CyclicBarrier(2);
        List<Object> results = runConcurrently(List.<Callable<Object>>of(
                () -> disableAfterBarrier(barrier, first.id(), first.version()),
                () -> disableAfterBarrier(barrier, second.id(), second.version())));

        assertThat(results.stream().filter(UserResult.class::isInstance))
                .as("两次并发停用只能成功一次")
                .hasSize(1);
        assertThat(results.stream()
                .filter(ApiException.class::isInstance)
                .map(ApiException.class::cast))
                .singleElement()
                .satisfies(exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("LAST_ADMIN_PROTECTED");
                });
        assertThat(enabledHolders(adminRole))
                .as("不变量：任何时刻都至少保留一个启用管理员")
                .isEqualTo(1);
    }

    // ---------- 替换角色 ----------

    @Test
    void replaceRolesAddsRemovesAndKeepsUntouchedGrantsUntouched() {
        long employeeRole = roleId(EMPLOYEE);
        long supportRole = roleId(IT_SUPPORT);
        long adminRole = roleId(SYSTEM_ADMIN);

        UserResult created = userService.create(new CreateUserCommand(
                "user-it-replace", "替换角色用户", "Password#2026",
                List.of(employeeRole, supportRole)));
        LocalDateTime supportGrantedAt = grantedAt(created.id(), supportRole);
        UserResult replaced = userService.replaceRoles(created.id(),
                new ReplaceUserRolesCommand(created.version(), List.of(supportRole, adminRole)));

        assertThat(replaced.roles()).extracting(summary -> summary.id())
                .containsExactly(supportRole, adminRole);
        assertThat(replaced.version()).isEqualTo(created.version() + 1);
        // 请求里没有的 EMPLOYEE 被删除，两边都有的 IT_SUPPORT 保持原审计字段不动
        assertThat(grantCount(created.id(), employeeRole)).isZero();
        assertThat(grantedAt(created.id(), supportRole)).isEqualTo(supportGrantedAt);
        assertThat(grantedBy(created.id(), adminRole)).isEqualTo(operatorId);
        verify(authSessionRepository).revokeAll(created.id());
    }

    @Test
    void replaceRolesToEmptySetLeavesZeroRoleEndState() {
        long employeeRole = roleId(EMPLOYEE);
        UserResult created = userService.create(new CreateUserCommand(
                "user-it-clear", "清空角色用户", "Password#2026", List.of(employeeRole)));

        UserResult replaced = userService.replaceRoles(created.id(),
                new ReplaceUserRolesCommand(created.version(), List.of()));

        assertThat(replaced.roles()).isEmpty();
        assertThat(grantCount(created.id(), employeeRole)).isZero();
        // 零角色是合法终态，用户仍然存在且启用
        assertThat(jdbc.queryForObject(
                "SELECT status FROM iam_user WHERE id = ?", String.class, created.id()))
                .isEqualTo("ENABLED");
    }

    @Test
    void replaceRolesIsIdempotentWhenSetIsUnchanged() {
        long employeeRole = roleId(EMPLOYEE);
        UserResult created = userService.create(new CreateUserCommand(
                "user-it-noop", "无变化用户", "Password#2026", List.of(employeeRole)));

        UserResult replaced = userService.replaceRoles(created.id(),
                new ReplaceUserRolesCommand(created.version(), List.of(employeeRole)));

        assertThat(replaced.version())
                .as("集合无实际变化时不推进版本")
                .isEqualTo(created.version());
        assertThat(grantedBy(created.id(), employeeRole)).isEqualTo(operatorId);
    }

    @Test
    void replaceRolesRejectsUnknownRoleAndKeepsGrantsUnchanged() {
        long employeeRole = roleId(EMPLOYEE);
        UserResult created = userService.create(new CreateUserCommand(
                "user-it-bad-role", "未知角色用户", "Password#2026", List.of(employeeRole)));

        assertApiError(
                () -> userService.replaceRoles(created.id(),
                        new ReplaceUserRolesCommand(created.version(),
                                List.of(employeeRole, Long.MAX_VALUE))),
                HttpStatus.NOT_FOUND,
                "ROLE_NOT_FOUND");

        assertThat(grantCount(created.id(), employeeRole))
                .as("整单失败时不得产生部分写入")
                .isEqualTo(1);
    }

    @Test
    void replaceRolesRejectsStaleVersion() {
        long employeeRole = roleId(EMPLOYEE);
        UserResult created = userService.create(new CreateUserCommand(
                "user-it-replace-stale", "替换版本用户", "Password#2026", List.of(employeeRole)));

        assertApiError(
                () -> userService.replaceRoles(created.id(),
                        new ReplaceUserRolesCommand(created.version() + 1, List.of())),
                HttpStatus.CONFLICT,
                "USER_CONFLICT");

        assertThat(grantCount(created.id(), employeeRole)).isEqualTo(1);
    }

    @Test
    void replaceRolesProtectsLastEnabledAdministrator() {
        long adminRole = roleId(SYSTEM_ADMIN);
        UserResult admin = userService.create(new CreateUserCommand(
                "user-it-replace-last-admin", "替换唯一管理员", "Password#2026",
                List.of(adminRole)));

        assertApiError(
                () -> userService.replaceRoles(admin.id(),
                        new ReplaceUserRolesCommand(admin.version(), List.of())),
                HttpStatus.CONFLICT,
                "LAST_ADMIN_PROTECTED");

        assertThat(grantCount(admin.id(), adminRole)).isEqualTo(1);
    }

    // ---------- 管理员重置密码 ----------

    @Test
    void resetPasswordBumpsVersionAndRevokesSessions() {
        UserResult created = userService.create(
                new CreateUserCommand("user-it-reset", "重置密码用户", "Password#2026", null));
        String before = jdbc.queryForObject(
                "SELECT password FROM iam_user WHERE id = ?", String.class, created.id());

        userService.resetPassword(created.id(),
                new ResetUserPasswordCommand(created.version(), "Reset#FlowDesk2026"));

        String after = jdbc.queryForObject(
                "SELECT password FROM iam_user WHERE id = ?", String.class, created.id());
        assertThat(after).isNotEqualTo(before).startsWith("$");
        assertThat(jdbc.queryForObject(
                "SELECT version FROM iam_user WHERE id = ?", Long.class, created.id()))
                .isEqualTo(created.version() + 1);
        verify(authSessionRepository).revokeAll(created.id());
    }

    @Test
    void resetPasswordRejectsStaleVersionWithoutChangingPassword() {
        UserResult created = userService.create(
                new CreateUserCommand("user-it-reset-stale", "重置版本用户", "Password#2026", null));
        String before = jdbc.queryForObject(
                "SELECT password FROM iam_user WHERE id = ?", String.class, created.id());

        assertApiError(
                () -> userService.resetPassword(created.id(),
                        new ResetUserPasswordCommand(created.version() + 1, "Reset#FlowDesk2026")),
                HttpStatus.CONFLICT,
                "USER_CONFLICT");

        assertThat(jdbc.queryForObject(
                "SELECT password FROM iam_user WHERE id = ?", String.class, created.id()))
                .isEqualTo(before);
    }

    // ---------- 辅助 ----------

    private UserResult createAfterBarrier(CyclicBarrier barrier, String username) throws Exception {
        barrier.await(30, TimeUnit.SECONDS);
        return userService.create(
                new CreateUserCommand(username, "并发创建用户", "Password#2026", null));
    }

    private UserResult disableAfterBarrier(CyclicBarrier barrier, long userId, long version)
            throws Exception {
        barrier.await(30, TimeUnit.SECONDS);
        return userService.disable(userId, new UserStatusChangeCommand(version));
    }

    /** 同时启动所有任务，返回每个任务的结果或异常，交由用例断言。 */
    private static List<Object> runConcurrently(List<Callable<Object>> tasks) throws Exception {        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
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

    private long insertUser(String username) {
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

    private long roleId(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM iam_role WHERE code = ?", Long.class, code);
    }

    private int grantCount(long userId, long roleId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_user_role
                WHERE user_id = ? AND role_id = ?
                """, Integer.class, userId, roleId);
    }

    private Long grantedBy(long userId, long roleId) {
        return jdbc.queryForObject("""
                SELECT granted_by FROM iam_user_role
                WHERE user_id = ? AND role_id = ?
                """, Long.class, userId, roleId);
    }

    private LocalDateTime grantedAt(long userId, long roleId) {
        return jdbc.queryForObject("""
                SELECT granted_at FROM iam_user_role
                WHERE user_id = ? AND role_id = ?
                """, LocalDateTime.class, userId, roleId);
    }

    private int enabledHolders(long roleId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_user user
                JOIN iam_user_role user_role ON user_role.user_id = user.id
                WHERE user_role.role_id = ? AND user.status = 'ENABLED'
                """, Integer.class, roleId);
    }

    private void assertApiError(
            ThrowingCallable invocation, HttpStatus expectedStatus, String expectedCode) {
        ApiException exception = catchThrowableOfType(invocation, ApiException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.status()).isEqualTo(expectedStatus);
        assertThat(exception.code()).isEqualTo(expectedCode);
    }
}
