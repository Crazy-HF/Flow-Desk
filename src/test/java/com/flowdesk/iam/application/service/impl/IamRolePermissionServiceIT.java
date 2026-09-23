package com.flowdesk.iam.application.service.impl;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.application.result.RolePermissionResult;
import com.flowdesk.iam.application.command.GrantRolePermissionsCommand;
import com.flowdesk.iam.application.query.RolePermissionQuery;
import com.flowdesk.iam.application.command.RevokeRolePermissionsCommand;
import com.flowdesk.iam.application.service.IamRolePermissionService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 角色权限授权服务的真实 MySQL 集成测试（`TASK-058` 第三、四片）。
 *
 * <p>不使用测试级 {@code @Transactional}：{@code SELECT ... FOR UPDATE} 与"先撤会话、后提交"的顺序
 * 必须依赖服务自身声明的事务。每个用例结束后显式清理 {@code ITRP} / {@code rp-it-} 前缀数据，
 * 内置 RBAC 基线保持不变。</p>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(
        classes = FlowDeskApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
class IamRolePermissionServiceIT {

    private static final String MYSQL_IMAGE = "mysql:8.4.11";
    private static final String ROLE_CODE_PREFIX = "ITRP";
    private static final String USERNAME_PREFIX = "rp-it-";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("flowdesk_role_permission_it")
            .withUsername("flowdesk")
            .withPassword("flowdesk");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private IamRolePermissionService rolePermissionService;

    @Autowired
    private JdbcTemplate jdbc;

    /** Redis 会话不在本切片，撤销调用只做转发，用替身观察调用与顺序。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    private long operatorId;
    private long permissionView;
    private long permissionCreate;

    @BeforeEach
    void prepareOperatorAndPermissions() {
        operatorId = insertUser("operator");
        permissionView = permissionId("TICKET_VIEW_OWN");
        permissionCreate = permissionId("TICKET_CREATE");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthPrincipal(operatorId, "rp-it-operator", "session"),
                        null,
                        List.of()));
    }

    @AfterEach
    void removeIntegrationTestData() {
        SecurityContextHolder.clearContext();
        jdbc.update("""
                DELETE role_permission
                FROM iam_role_permission role_permission
                JOIN iam_role role ON role.id = role_permission.role_id
                WHERE role.code LIKE 'ITRP%'
                """);
        jdbc.update("""
                DELETE role_permission
                FROM iam_role_permission role_permission
                JOIN iam_user grantor ON grantor.id = role_permission.granted_by
                WHERE grantor.username LIKE 'rp-it-%'
                """);
        jdbc.update("""
                DELETE user_role
                FROM iam_user_role user_role
                JOIN iam_user user ON user.id = user_role.user_id
                WHERE user.username LIKE 'rp-it-%'
                """);
        jdbc.update("""
                DELETE user_role
                FROM iam_user_role user_role
                JOIN iam_role role ON role.id = user_role.role_id
                WHERE role.code LIKE 'ITRP%'
                """);
        jdbc.update("""
                DELETE user_role
                FROM iam_user_role user_role
                JOIN iam_user grantor ON grantor.id = user_role.granted_by
                WHERE grantor.username LIKE 'rp-it-%'
                """);
        jdbc.update("DELETE FROM iam_user WHERE username LIKE 'rp-it-%'");
        jdbc.update("DELETE FROM iam_role WHERE code LIKE 'ITRP%'");
    }

    // ---------- 批量增量授予 ----------

    @Test
    void grantsOnlyMissingPermissionsWithAuditAndRevokesRoleUsersOnce() {
        long roleId = insertRole("ITRP_GRANT");
        long firstUserId = insertUser("grant-a");
        long secondUserId = insertUser("grant-b");
        long unrelatedUserId = insertUser("grant-c");
        insertUserRole(firstUserId, roleId);
        insertUserRole(secondUserId, roleId);
        insertRolePermission(roleId, permissionView, null);

        List<RolePermissionResult> result = rolePermissionService.grant(
                new GrantRolePermissionsCommand(
                        roleId, List.of(permissionCreate, permissionView, permissionCreate)));

        // 服务端对目标 ID 去重并按主键升序归一化，返回值也按该顺序
        assertThat(result).extracting(RolePermissionResult::permissionId)
                .containsExactly(permissionCreate, permissionView);
        assertThat(result.getFirst().grantedBy())
                .as("新增关系写入审计操作人")
                .isEqualTo(operatorId);
        assertThat(result.get(1).grantedBy())
                .as("已有关系保留原审计字段，不被重写")
                .isNull();
        assertThat(result.getFirst().roleCode()).isEqualTo("ITRP_GRANT");
        assertThat(result.getFirst().permissionCode()).isEqualTo("TICKET_CREATE");

        assertThat(jdbc.queryForObject("""
                SELECT granted_by FROM iam_role_permission
                WHERE role_id = ? AND permission_id = ?
                """, Long.class, roleId, permissionCreate)).isEqualTo(operatorId);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_role_permission
                WHERE role_id = ? AND granted_at IS NOT NULL
                """, Integer.class, roleId)).isEqualTo(1);

        InOrder revocations = inOrder(authSessionRepository);
        revocations.verify(authSessionRepository).revokeAll(firstUserId);
        revocations.verify(authSessionRepository).revokeAll(secondUserId);
        verify(authSessionRepository, times(2)).revokeAll(anyLong());
        verify(authSessionRepository, never()).revokeAll(unrelatedUserId);
    }

    @Test
    void repeatedGrantIsIdempotentAndDoesNotRevokeSessions() {
        long roleId = insertRole("ITRP_IDEMPOTENT");
        long userId = insertUser("idempotent");
        insertUserRole(userId, roleId);

        rolePermissionService.grant(
                new GrantRolePermissionsCommand(roleId, List.of(permissionView)));
        clearInvocations(authSessionRepository);

        rolePermissionService.grant(
                new GrantRolePermissionsCommand(roleId, List.of(permissionView)));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_role_permission WHERE role_id = ?
                """, Integer.class, roleId)).isEqualTo(1);
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void rejectsWholeBatchWhenAnyPermissionDoesNotExistWithoutPartialWrite() {
        long roleId = insertRole("ITRP_ROLLBACK");
        long userId = insertUser("rollback");
        insertUserRole(userId, roleId);

        assertApiError(
                () -> rolePermissionService.grant(new GrantRolePermissionsCommand(
                        roleId, List.of(permissionView, Long.MAX_VALUE))),
                HttpStatus.NOT_FOUND,
                "PERMISSION_NOT_FOUND");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_role_permission WHERE role_id = ?
                """, Integer.class, roleId)).isZero();
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void rejectsMissingRole() {
        assertApiError(
                () -> rolePermissionService.grant(new GrantRolePermissionsCommand(
                        Long.MAX_VALUE, List.of(permissionView))),
                HttpStatus.NOT_FOUND,
                "ROLE_NOT_FOUND");

        verifyNoInteractions(authSessionRepository);
    }

    // ---------- 单条撤销与保护规则 ----------

    @Test
    void revokeRemovesSingleGrantAndRevokesEveryRoleUser() {
        long roleId = insertRole("ITRP_REVOKE");
        long firstUserId = insertUser("revoke-a");
        long secondUserId = insertUser("revoke-b");
        insertUserRole(firstUserId, roleId);
        insertUserRole(secondUserId, roleId);
        insertRolePermission(roleId, permissionView, null);
        insertRolePermission(roleId, permissionCreate, null);

        rolePermissionService.revoke(roleId, permissionCreate);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_role_permission
                WHERE role_id = ? AND permission_id = ?
                """, Integer.class, roleId, permissionCreate)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_role_permission
                WHERE role_id = ? AND permission_id = ?
                """, Integer.class, roleId, permissionView)).isEqualTo(1);

        InOrder revocations = inOrder(authSessionRepository);
        revocations.verify(authSessionRepository).revokeAll(firstUserId);
        revocations.verify(authSessionRepository).revokeAll(secondUserId);
    }

    @Test
    void protectsSystemAdminRbacManageGrantFromRevocation() {
        long systemAdmin = roleId("SYSTEM_ADMIN");
        long rbacManage = permissionId("RBAC_MANAGE");

        assertApiError(() -> rolePermissionService.revoke(systemAdmin, rbacManage),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_role_permission
                WHERE role_id = ? AND permission_id = ?
                """, Integer.class, systemAdmin, rbacManage)).isEqualTo(1);
        verifyNoInteractions(authSessionRepository);
    }

    // ---------- 批量撤销与清空（TASK-058 增补端点） ----------

    @Test
    void revokeBatchRemovesRequestedPermissionsAndRevokesRoleUsersOnce() {
        long roleId = insertRole("ITRP_BATCH");
        long firstUserId = insertUser("batch-a");
        long secondUserId = insertUser("batch-b");
        insertUserRole(firstUserId, roleId);
        insertUserRole(secondUserId, roleId);
        insertRolePermission(roleId, permissionView, null);
        insertRolePermission(roleId, permissionCreate, null);

        rolePermissionService.revokeBatch(new RevokeRolePermissionsCommand(
                roleId, List.of(permissionCreate, permissionView, permissionCreate)));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_role_permission WHERE role_id = ?
                """, Integer.class, roleId)).isZero();

        InOrder revocations = inOrder(authSessionRepository);
        revocations.verify(authSessionRepository).revokeAll(firstUserId);
        revocations.verify(authSessionRepository).revokeAll(secondUserId);
    }

    @Test
    void revokeBatchRejectsWholeBatchWhenAnyGrantIsMissingWithoutWrite() {
        long roleId = insertRole("ITRP_BATCH_MISSING");
        long userId = insertUser("batch-missing");
        insertUserRole(userId, roleId);
        insertRolePermission(roleId, permissionView, null);

        assertApiError(
                () -> rolePermissionService.revokeBatch(new RevokeRolePermissionsCommand(
                        roleId, List.of(permissionView, permissionCreate))),
                HttpStatus.NOT_FOUND,
                "GRANT_NOT_FOUND");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_role_permission WHERE role_id = ?
                """, Integer.class, roleId)).isEqualTo(1);
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void revokeBatchProtectsSystemAdminRbacManage() {
        long systemAdmin = roleId("SYSTEM_ADMIN");
        long rbacManage = permissionId("RBAC_MANAGE");

        assertApiError(
                () -> rolePermissionService.revokeBatch(new RevokeRolePermissionsCommand(
                        systemAdmin, List.of(rbacManage))),
                HttpStatus.CONFLICT,
                "RBAC_CONFLICT");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_role_permission
                WHERE role_id = ? AND permission_id = ?
                """, Integer.class, systemAdmin, rbacManage)).isEqualTo(1);
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void clearAllRemovesEveryPermissionAndIsIdempotent() {
        long roleId = insertRole("ITRP_CLEAR");
        long firstUserId = insertUser("clear-a");
        long secondUserId = insertUser("clear-b");
        insertUserRole(firstUserId, roleId);
        insertUserRole(secondUserId, roleId);
        insertRolePermission(roleId, permissionView, null);
        insertRolePermission(roleId, permissionCreate, null);

        rolePermissionService.clearAll(roleId);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_role_permission WHERE role_id = ?
                """, Integer.class, roleId)).isZero();
        InOrder revocations = inOrder(authSessionRepository);
        revocations.verify(authSessionRepository).revokeAll(firstUserId);
        revocations.verify(authSessionRepository).revokeAll(secondUserId);

        clearInvocations(authSessionRepository);
        rolePermissionService.clearAll(roleId);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_role_permission WHERE role_id = ?
                """, Integer.class, roleId)).isZero();
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void clearAllProtectsSystemAdminRbacManage() {
        long systemAdmin = roleId("SYSTEM_ADMIN");
        long rbacManage = permissionId("RBAC_MANAGE");

        assertApiError(() -> rolePermissionService.clearAll(systemAdmin),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM iam_role_permission
                WHERE role_id = ? AND permission_id = ?
                """, Integer.class, systemAdmin, rbacManage)).isEqualTo(1);
        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void clearAllRejectsMissingRole() {
        assertApiError(() -> rolePermissionService.clearAll(Long.MAX_VALUE),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");

        verifyNoInteractions(authSessionRepository);
    }

    @Test
    void rejectsRevokeWhenGrantDoesNotExist() {
        long roleId = insertRole("ITRP_NO_GRANT");
        long userId = insertUser("no-grant");
        insertUserRole(userId, roleId);

        assertApiError(() -> rolePermissionService.revoke(roleId, permissionView),
                HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND");

        verifyNoInteractions(authSessionRepository);
    }

    // ---------- 分页 ----------

    @Test
    void pagesRealRowsForTargetRoleWithPermissionIdOrderAndJoinedNames() {
        long roleId = insertRole("ITRP_PAGE");
        insertRolePermission(roleId, permissionView, null);
        insertRolePermission(roleId, permissionCreate, null);

        RolePermissionQuery query = new RolePermissionQuery();
        query.setRoleId(roleId);
        query.setOrderBy("permission_id");
        query.setOrderDirection("desc");

        PageResult<RolePermissionResult> page = rolePermissionService.page(query);

        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.items()).extracting(RolePermissionResult::permissionId)
                .containsExactly(
                        Math.max(permissionView, permissionCreate),
                        Math.min(permissionView, permissionCreate));
        assertThat(page.items().getFirst().roleCode()).isEqualTo("ITRP_PAGE");
        assertThat(page.items().getFirst().permissionCode()).isNotBlank();
        assertThat(page.items().getFirst().grantedAt()).isNull();
    }

    // ---------- 辅助 ----------

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

    private long permissionId(String code) {
        return jdbc.queryForObject(
                "SELECT id FROM iam_permission WHERE code = ?", Long.class, code);
    }

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

    private void insertUserRole(long userId, long roleId) {
        jdbc.update("""
                INSERT INTO iam_user_role (user_id, role_id, granted_by, granted_at)
                VALUES (?, ?, NULL, CURRENT_TIMESTAMP(3))
                """, userId, roleId);
    }

    private void insertRolePermission(long roleId, long permissionId, Long grantedBy) {
        jdbc.update("""
                INSERT INTO iam_role_permission (role_id, permission_id, granted_by, granted_at)
                VALUES (?, ?, ?, NULL)
                """, roleId, permissionId, grantedBy);
    }

    private void assertApiError(
            ThrowingCallable invocation, HttpStatus expectedStatus, String expectedCode) {
        ApiException exception = catchThrowableOfType(invocation, ApiException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.status()).isEqualTo(expectedStatus);
        assertThat(exception.code()).isEqualTo(expectedCode);
    }
}
