package com.flowdesk.iam.application.service.impl;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.application.result.UserRoleResult;
import com.flowdesk.iam.application.command.GrantRoleToUsersCommand;
import com.flowdesk.iam.application.command.GrantUserRolesCommand;
import com.flowdesk.iam.application.query.UserRoleQuery;
import com.flowdesk.iam.application.command.RevokeUserRolesCommand;
import com.flowdesk.iam.application.service.IamUserRoleService;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 用户角色授权服务的真实 MySQL 集成测试（`TASK-058` 第二、四片）。
 *
 * <p>不使用测试级 {@code @Transactional}：{@code SELECT ... FOR UPDATE} 必须依赖服务自身声明的事务，
 * 否则测试外层事务会掩盖生产事务边界缺失。每个用例结束后显式清理 {@code ITUR} / {@code ur-it-} 前缀数据，
 * 内置 RBAC 基线保持不变。</p>
 *
 * <p>竞态用例是阶段验收要求的真并发证据：固定锁顺序下不允许死锁；并发撤销同一用户的全部角色时两次都成功且终态零授权（2026-09-22 起零角色是合法终态），并发撤销各自的管理员角色时只能有一个成功、终态仍有至少一个启用管理员。mock 返回固定计数不能替代这两条。</p>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = FlowDeskApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
class IamUserRoleServiceIT {

    private static final String MYSQL_IMAGE = "mysql:8.4.11";
    private static final String ROLE_CODE_PREFIX = "ITUR";
    private static final String USERNAME_PREFIX = "ur-it-";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("flowdesk_user_role_it")
            .withUsername("flowdesk")
            .withPassword("flowdesk");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private IamUserRoleService userRoleService;

    @Autowired
    private JdbcTemplate jdbc;

    /** Redis 会话不在本切片，撤销调用只做转发，用替身观察调用与顺序。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    private long operatorId;

    @BeforeEach
    void prepareOperator() {
        operatorId = insertUser("operator", "ENABLED");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(operatorId, "ur-it-operator", "session"),
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
                WHERE user.username LIKE 'ur-it-%'
                """);
        jdbc.update("""
                DELETE user_role
                FROM iam_user_role user_role
                JOIN iam_role role ON role.id = user_role.role_id
                WHERE role.code LIKE 'ITUR%'
                """);
        jdbc.update("""
                DELETE user_role
                FROM iam_user_role user_role
                JOIN iam_user grantor ON grantor.id = user_role.granted_by
                WHERE grantor.username LIKE 'ur-it-%'
                """);
        jdbc.update("""
                DELETE role_permission
                FROM iam_role_permission role_permission
                JOIN iam_role role ON role.id = role_permission.role_id
                WHERE role.code LIKE 'ITUR%'
                """);
        jdbc.update("DELETE FROM iam_user WHERE username LIKE 'ur-it-%'");
        jdbc.update("DELETE FROM iam_role WHERE code LIKE 'ITUR%'");
    }

    // ---------- 批量增量授予 ----------

    @Test
    void persistsOnlyMissingGrantsWithAuditAndRevokesTargetUserOnce() {
        long roleA = insertRole("ITUR_A");
        long roleB = insertRole("ITUR_B");
        long userId = insertUser("grant-target", "ENABLED");
        long otherUserId = insertUser("grant-other", "ENABLED");
        insertUserRole(userId, roleA, null);
        insertUserRole(otherUserId, roleB, null);

        List<UserRoleResult> result = userRoleService.grant(
                new GrantUserRolesCommand(userId, List.of(roleB, roleA, roleB)));

        assertThat(result).extracting(UserRoleResult::roleId).containsExactly(roleA, roleB);
        assertThat(result.getFirst().grantedBy()).isNull();
        assertThat(result.get(1).grantedBy()).isEqualTo(operatorId);
        assertThat(result.get(1).username()).isEqualTo(username("grant-target"));
        assertThat(result.get(1).roleCode()).isEqualTo("ITUR_B");

        assertThat(countUserRoles(userId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT granted_by FROM iam_user_role WHERE user_id = ? AND role_id = ?
                """, Long.class, userId, roleB)).isEqualTo(operatorId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_user_role
                WHERE user_id = ? AND role_id = ? AND granted_at IS NOT NULL
                """, Integer.class, userId, roleB)).isEqualTo(1);

        verify(authSessionRepository, times(1)).revokeAll(userId);
        verify(authSessionRepository, never()).revokeAll(otherUserId);
    }

    @Test
    void repeatedGrantIsIdempotentAndKeepsOriginalAudit() {
        long roleA = insertRole("ITUR_IDEMPOTENT");
        long userId = insertUser("idempotent", "ENABLED");

        userRoleService.grant(new GrantUserRolesCommand(userId, List.of(roleA)));
        LocalDateTime firstGrantedAt = grantedAt(userId, roleA);
        Long firstGrantedBy = jdbc.queryForObject("""
                SELECT granted_by FROM iam_user_role WHERE user_id = ? AND role_id = ?
                """, Long.class, userId, roleA);
        clearInvocations(authSessionRepository);

        userRoleService.grant(new GrantUserRolesCommand(userId, List.of(roleA)));

        assertThat(countUserRoles(userId)).isEqualTo(1);
        assertThat(grantedAt(userId, roleA)).isEqualTo(firstGrantedAt);
        assertThat(jdbc.queryForObject("""
                SELECT granted_by FROM iam_user_role WHERE user_id = ? AND role_id = ?
                """, Long.class, userId, roleA)).isEqualTo(firstGrantedBy);
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void rejectsWholeBatchWhenAnyRoleDoesNotExistWithoutPartialWrite() {
        long roleA = insertRole("ITUR_ROLLBACK");
        long userId = insertUser("rollback", "ENABLED");

        assertApiError(
                () -> userRoleService.grant(new GrantUserRolesCommand(
                        userId, List.of(roleA, Long.MAX_VALUE))),
                HttpStatus.NOT_FOUND,
                "ROLE_NOT_FOUND");

        assertThat(countUserRoles(userId)).isZero();
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void rejectsMissingUser() {
        long roleA = insertRole("ITUR_MISSING_USER");

        assertApiError(
                () -> userRoleService.grant(new GrantUserRolesCommand(
                        Long.MAX_VALUE, List.of(roleA))),
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND");

        verifyNoInteractions(authSessionRepository);
    }

    // ---------- 单条撤销与保护规则 ----------

    @Test
    void revokeRemovesSingleGrantAndRevokesSessionBeforeCommit() {
        long roleA = insertRole("ITUR_REVOKE_A");
        long roleB = insertRole("ITUR_REVOKE_B");
        long userId = insertUser("revoke", "ENABLED");
        insertUserRole(userId, roleA, null);
        insertUserRole(userId, roleB, null);

        userRoleService.revoke(userId, roleA);

        assertThat(countUserRoles(userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_user_role WHERE user_id = ? AND role_id = ?
                """, Integer.class, userId, roleA)).isZero();
        verify(authSessionRepository, times(1)).revokeAll(userId);
    }

    /**
     * 决策变更（2026-09-22 用户确认）：废弃"用户必须至少保留一个角色"的保护规则，
     * 允许撤销后用户不再拥有任何角色。
     */
    @Test
    void allowsRevokingUsersLastRemainingRole() {
        long roleA = insertRole("ITUR_LAST_ROLE");
        long roleB = insertRole("ITUR_LAST_ROLE_OTHER");
        long userId = insertUser("last-role", "ENABLED");
        insertUserRole(userId, roleA, null);
        insertUserRole(userId, roleB, null);

        userRoleService.revoke(userId, roleA);
        userRoleService.revoke(userId, roleB);

        assertThat(countUserRoles(userId)).isZero();
    }

    // ---------- 批量撤销与清空（TASK-058 增补端点） ----------

    @Test
    void revokeBatchRemovesRequestedRolesAndRevokesSessionOnce() {
        long roleA = insertRole("ITUR_BATCH_A");
        long roleB = insertRole("ITUR_BATCH_B");
        long roleC = insertRole("ITUR_BATCH_C");
        long userId = insertUser("batch", "ENABLED");
        for (long grantedRole : List.of(roleA, roleB, roleC)) {
            insertUserRole(userId, grantedRole, null);
        }

        userRoleService.revokeBatch(new RevokeUserRolesCommand(
                userId, List.of(roleB, roleA, roleB)));

        assertThat(countUserRoles(userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_user_role WHERE user_id = ? AND role_id = ?
                """, Integer.class, userId, roleC)).isEqualTo(1);
        verify(authSessionRepository, times(1)).revokeAll(userId);
    }

    @Test
    void revokeBatchRejectsWholeBatchWhenAnyGrantIsMissingWithoutWrite() {
        long roleA = insertRole("ITUR_BATCH_MISSING_A");
        long roleB = insertRole("ITUR_BATCH_MISSING_B");
        long userId = insertUser("batch-missing", "ENABLED");
        insertUserRole(userId, roleA, null);

        assertApiError(
                () -> userRoleService.revokeBatch(new RevokeUserRolesCommand(
                        userId, List.of(roleA, roleB))),
                HttpStatus.NOT_FOUND,
                "GRANT_NOT_FOUND");

        assertThat(countUserRoles(userId)).isEqualTo(1);
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void clearAllRemovesEveryRoleAndIsIdempotent() {
        long roleA = insertRole("ITUR_CLEAR_A");
        long roleB = insertRole("ITUR_CLEAR_B");
        long userId = insertUser("clear", "ENABLED");
        insertUserRole(userId, roleA, null);
        insertUserRole(userId, roleB, null);

        userRoleService.clearAll(userId);

        assertThat(countUserRoles(userId)).isZero();
        verify(authSessionRepository, times(1)).revokeAll(userId);

        clearInvocations(authSessionRepository);
        userRoleService.clearAll(userId);

        assertThat(countUserRoles(userId)).isZero();
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void clearAllProtectsLastEnabledAdministrator() {
        long systemAdmin = roleId("SYSTEM_ADMIN");
        long filler = insertRole("ITUR_CLEAR_FILLER");
        long soleAdmin = insertUser("clear-sole-admin", "ENABLED");
        insertUserRole(soleAdmin, systemAdmin, null);
        insertUserRole(soleAdmin, filler, null);

        assertApiError(() -> userRoleService.clearAll(soleAdmin),
                HttpStatus.CONFLICT, "LAST_ADMIN_PROTECTED");

        assertThat(countUserRoles(soleAdmin)).isEqualTo(2);
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void grantUsersGrantsRoleToMissingUsersAndRevokesEachTargetOnce() {
        long roleId = insertRole("ITUR_GRANT_USERS");
        long firstUserId = insertUser("grant-users-a", "ENABLED");
        long secondUserId = insertUser("grant-users-b", "ENABLED");
        insertUserRole(firstUserId, roleId, null);

        List<UserRoleResult> result = userRoleService.grantUsers(
                new GrantRoleToUsersCommand(roleId, List.of(secondUserId, firstUserId, secondUserId)));

        assertThat(result).extracting(UserRoleResult::userId)
                .containsExactly(firstUserId, secondUserId);
        assertThat(result.getFirst().grantedBy()).isNull();
        assertThat(result.get(1).grantedBy()).isEqualTo(operatorId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_user_role WHERE role_id = ?
                """, Integer.class, roleId)).isEqualTo(2);

        verify(authSessionRepository, times(1)).revokeAll(secondUserId);
        verify(authSessionRepository, never()).revokeAll(firstUserId);
    }

    @Test
    void grantUsersRejectsWholeBatchWhenAnyUserIsMissingWithoutWrite() {
        long roleId = insertRole("ITUR_GRANT_USERS_MISSING");
        long userId = insertUser("grant-users-missing", "ENABLED");

        assertApiError(
                () -> userRoleService.grantUsers(new GrantRoleToUsersCommand(
                        roleId, List.of(userId, Long.MAX_VALUE))),
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_user_role WHERE role_id = ?
                """, Integer.class, roleId)).isZero();
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void protectsLastEnabledAdministratorButAllowsRevokeWhenAnotherRemains() {
        long systemAdmin = roleId("SYSTEM_ADMIN");
        long filler = insertRole("ITUR_ADMIN_FILLER");
        long soleAdmin = insertUser("sole-admin", "ENABLED");
        insertUserRole(soleAdmin, systemAdmin, null);
        insertUserRole(soleAdmin, filler, null);

        assertApiError(() -> userRoleService.revoke(soleAdmin, systemAdmin),
                HttpStatus.CONFLICT, "LAST_ADMIN_PROTECTED");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_user_role WHERE user_id = ? AND role_id = ?
                """, Integer.class, soleAdmin, systemAdmin)).isEqualTo(1);

        long secondAdmin = insertUser("second-admin", "ENABLED");
        insertUserRole(secondAdmin, systemAdmin, null);
        insertUserRole(secondAdmin, filler, null);

        userRoleService.revoke(soleAdmin, systemAdmin);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_user_role WHERE user_id = ? AND role_id = ?
                """, Integer.class, soleAdmin, systemAdmin)).isZero();
        assertThat(countEnabledAdministrators(systemAdmin)).isEqualTo(1);
    }

    @Test
    void rejectsRevokeWhenGrantDoesNotExist() {
        long roleA = insertRole("ITUR_NO_GRANT");
        long roleB = insertRole("ITUR_NO_GRANT_OTHER");
        long userId = insertUser("no-grant", "ENABLED");
        insertUserRole(userId, roleA, null);

        assertApiError(() -> userRoleService.revoke(userId, roleB),
                HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND");

        verifyNoInteractions(authSessionRepository);
    }

    // ---------- 分页 ----------

    @Test
    void pagesRealRowsForTargetUserWithGrantedAtOrderAndJoinedNames() {
        long roleA = insertRole("ITUR_PAGE_A");
        long roleB = insertRole("ITUR_PAGE_B");
        long userId = insertUser("page", "ENABLED");
        insertUserRole(userId, roleA, null);
        insertUserRole(userId, roleB, null);
        jdbc.update("""
                UPDATE iam_user_role SET granted_at = '2026-09-20 01:00:00.000'
                WHERE user_id = ? AND role_id = ?
                """, userId, roleA);
        jdbc.update("""
                UPDATE iam_user_role SET granted_at = '2026-09-21 01:00:00.000'
                WHERE user_id = ? AND role_id = ?
                """, userId, roleB);

        UserRoleQuery query = new UserRoleQuery();
        query.setUserId(userId);
        query.setOrderBy("granted_at");
        query.setOrderDirection("desc");

        PageResult<UserRoleResult> page = userRoleService.page(query);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).extracting(UserRoleResult::roleId)
                .containsExactly(roleB, roleA);
        assertThat(page.items().getFirst().username()).isEqualTo(username("page"));
        assertThat(page.items().getFirst().roleCode()).isEqualTo("ITUR_PAGE_B");
        assertThat(page.items().getFirst().grantedAt().getOffset().getId()).isEqualTo("Z");
    }

    // ---------- 真并发：固定锁顺序下不留中间态 ----------

    /**
     * 并发撤销同一用户的两个角色：两条事务都先锁各自角色、再在用户行上串行，
     * 因此都应成功、无死锁，最终该用户不再持有任何角色（决策变更后允许 0 角色）。
     */
    @Test
    void concurrentRevokeOfBothRolesSucceedsWithoutDeadlockAndLeavesNoGrant()
            throws Exception {
        long roleA = insertRole("ITUR_RACE_A");
        long roleB = insertRole("ITUR_RACE_B");
        long userId = insertUser("race-roles", "ENABLED");
        insertUserRole(userId, roleA, null);
        insertUserRole(userId, roleB, null);

        List<Throwable> failures = runConcurrently(
                () -> userRoleService.revoke(userId, roleA),
                () -> userRoleService.revoke(userId, roleB));

        assertThat(apiErrorCodes(failures))
                .as("同一用户两个角色的并发撤销不应产生死锁或冲突")
                .isEmpty();
        assertThat(countUserRoles(userId))
                .as("并发撤销后两个角色都应被移除")
                .isZero();
    }

    @Test
    void concurrentRevokeOfOwnAdminRoleLeavesAtLeastOneEnabledAdministrator()
            throws Exception {
        long systemAdmin = roleId("SYSTEM_ADMIN");
        long filler = insertRole("ITUR_RACE_ADMIN_FILLER");
        long firstAdmin = insertUser("race-admin-a", "ENABLED");
        long secondAdmin = insertUser("race-admin-b", "ENABLED");
        for (long admin : List.of(firstAdmin, secondAdmin)) {
            insertUserRole(admin, systemAdmin, null);
            insertUserRole(admin, filler, null);
        }

        List<Throwable> failures = runConcurrently(
                () -> userRoleService.revoke(firstAdmin, systemAdmin),
                () -> userRoleService.revoke(secondAdmin, systemAdmin));

        assertThat(failures).filteredOn(java.util.Objects::isNull).hasSize(1);
        assertThat(apiErrorCodes(failures)).containsExactly("LAST_ADMIN_PROTECTED");
        assertThat(countEnabledAdministrators(systemAdmin))
                .as("并发撤销各自管理员角色后，必须仍有一个启用管理员")
                .isEqualTo(1);
    }

    // ---------- 辅助 ----------

    /**
     * 同时启动两个写用例，返回与入参顺序一致的异常列表（成功为 {@code null}）。
     *
     * <p>返回后立即断言数据库不变量，等价于"两个事务都已结束后再检查"，因此不会把中间态当成终态。</p>
     */
    private List<Throwable> runConcurrently(Runnable first, Runnable second)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            Future<Throwable> firstResult = executor.submit(
                    () -> runAfterGate(startGate, first));
            Future<Throwable> secondResult = executor.submit(
                    () -> runAfterGate(startGate, second));
            startGate.countDown();
            return java.util.Arrays.asList(
                    firstResult.get(60, TimeUnit.SECONDS),
                    secondResult.get(60, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    /** 把并发结果中的非空异常统一断言为 {@link ApiException}，再取出稳定错误码。 */
    private static List<String> apiErrorCodes(List<Throwable> failures) {
        return failures.stream()
                .filter(java.util.Objects::nonNull)
                .map(failure -> {
                    assertThat(failure).isInstanceOf(ApiException.class);
                    return ((ApiException) failure).code();
                })
                .toList();
    }

    private static Throwable runAfterGate(CountDownLatch startGate, Runnable action)
            throws InterruptedException {
        startGate.await(10, TimeUnit.SECONDS);
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private long insertRole(String code) {
        jdbc.update("""
                INSERT INTO iam_role (code, name, description, created_at)
                VALUES (?, ?, NULL, CURRENT_TIMESTAMP(3))
                """, code, code);
        return roleId(code);
    }

    private long roleId(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM iam_role WHERE code = ?", Long.class, code);
    }

    private long insertUser(String suffix, String status) {
        String username = username(suffix);
        jdbc.update("""
                INSERT INTO iam_user (
                    username, display_name, password, status,
                    created_at, updated_at, version
                ) VALUES (?, ?, 'integration-test-hash', ?,
                          CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 0)
                """, username, username, status);
        return jdbc.queryForObject(
                "SELECT id FROM iam_user WHERE username = ?", Long.class, username);
    }

    private static String username(String suffix) {
        return USERNAME_PREFIX + suffix;
    }

    private void insertUserRole(long userId, long roleId, Long grantedBy) {
        jdbc.update("""
                INSERT INTO iam_user_role (user_id, role_id, granted_by, granted_at)
                VALUES (?, ?, ?, CURRENT_TIMESTAMP(3))
                """, userId, roleId, grantedBy);
    }

    private int countUserRoles(long userId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_user_role WHERE user_id = ?",
                Integer.class, userId);
    }

    private long countEnabledAdministrators(long systemAdminRoleId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM iam_user_role user_role
                JOIN iam_user user ON user.id = user_role.user_id
                WHERE user_role.role_id = ? AND user.status = 'ENABLED'
                """, Long.class, systemAdminRoleId);
    }

    private LocalDateTime grantedAt(long userId, long roleId) {
        return jdbc.queryForObject("""
                SELECT granted_at FROM iam_user_role WHERE user_id = ? AND role_id = ?
                """, LocalDateTime.class, userId, roleId);
    }

    private void assertApiError(
            ThrowingCallable invocation, HttpStatus expectedStatus, String expectedCode) {
        ApiException exception = catchThrowableOfType(invocation, ApiException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.status()).isEqualTo(expectedStatus);
        assertThat(exception.code()).isEqualTo(expectedCode);
    }
}
