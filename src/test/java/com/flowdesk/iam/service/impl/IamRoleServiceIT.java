package com.flowdesk.iam.service.impl;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.domain.bo.IamRoleBO;
import com.flowdesk.iam.domain.vo.IamRoleCreateVO;
import com.flowdesk.iam.domain.vo.IamRoleQueryVO;
import com.flowdesk.iam.domain.vo.IamRoleUpdateVO;
import com.flowdesk.iam.service.IamRoleService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 角色服务的真实 MySQL 集成测试。
 *
 * <p>这里不使用测试级 {@code @Transactional}：更新、删除中的 {@code SELECT ... FOR UPDATE}
 * 必须依赖服务自身声明的事务，避免测试外层事务掩盖生产事务边界缺失。每个用例结束后显式清理
 * {@code ITROLE} 前缀数据，内置 RBAC 基线保持不变。</p>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
class IamRoleServiceIT {

    private static final String MYSQL_IMAGE = "mysql:8.4.11";
    private static final String ROLE_CODE_PREFIX = "ITROLE";
    private static final String USERNAME_PREFIX = "role-it-";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("flowdesk_role_it")
            .withUsername("flowdesk")
            .withPassword("flowdesk");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private IamRoleService roleService;

    @Autowired
    private JdbcTemplate jdbc;

    /** Redis 不属于本切片，使用替身满足认证模块依赖。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    @AfterEach
    void removeIntegrationTestData() {
        jdbc.update("""
                DELETE role_permission
                FROM iam_role_permission role_permission
                JOIN iam_role role ON role.id = role_permission.role_id
                WHERE role.code LIKE 'ITROLE%'
                """);
        jdbc.update("""
                DELETE user_role
                FROM iam_user_role user_role
                JOIN iam_role role ON role.id = user_role.role_id
                WHERE role.code LIKE 'ITROLE%'
                """);
        jdbc.update("DELETE FROM iam_user WHERE username LIKE 'role-it-%'");
        jdbc.update("DELETE FROM iam_role WHERE code LIKE 'ITROLE%'");
    }

    @Test
    void createsReadsUpdatesAndDeletesUnreferencedCustomRole() {
        IamRoleBO created = roleService.create(
                new IamRoleCreateVO("ITROLE_CRUD", "集成测试角色", "创建说明"));

        assertThat(created.id()).isPositive();
        assertThat(created.code()).isEqualTo("ITROLE_CRUD");
        assertThat(created.name()).isEqualTo("集成测试角色");
        assertThat(created.description()).isEqualTo("创建说明");
        assertThat(created.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(created.permissionIds()).isEmpty();

        IamRoleBO found = roleService.getById(created.id());
        assertThat(found.code()).isEqualTo("ITROLE_CRUD");
        assertThat(found.permissionIds()).isEmpty();

        IamRoleBO updated = roleService.update(
                created.id(), new IamRoleUpdateVO("已更新角色", null));
        assertThat(updated.code()).isEqualTo("ITROLE_CRUD");
        assertThat(updated.name()).isEqualTo("已更新角色");
        assertThat(updated.description()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT code FROM iam_role WHERE id = ?", String.class, created.id()))
                .isEqualTo("ITROLE_CRUD");

        roleService.delete(created.id());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_role WHERE id = ?", Integer.class, created.id()))
                .isZero();
        assertApiError(() -> roleService.getById(created.id()),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");
    }

    @Test
    void pagesRealRowsWithStableOrderAndOrderedPermissionIds() {
        IamRoleBO first = roleService.create(
                new IamRoleCreateVO("ITROLE_PAGE_A", "同名分页角色", null));
        IamRoleBO second = roleService.create(
                new IamRoleCreateVO("ITROLE_PAGE_B", "同名分页角色", null));
        roleService.create(new IamRoleCreateVO("ITROLE_PAGE_C", "同名分页角色", null));

        List<Long> permissionIds = jdbc.queryForList(
                "SELECT id FROM iam_permission ORDER BY id DESC LIMIT 2", Long.class);
        jdbc.update("""
                INSERT INTO iam_role_permission (role_id, permission_id, granted_by, granted_at)
                VALUES (?, ?, NULL, CURRENT_TIMESTAMP(6)), (?, ?, NULL, CURRENT_TIMESTAMP(6))
                """,
                first.id(), permissionIds.get(0), first.id(), permissionIds.get(1));

        IamRoleQueryVO query = new IamRoleQueryVO();
        query.setPageNo(1);
        query.setPageSize(2);
        query.setKeyword("ITROLE_PAGE");
        query.setOrderBy("name");
        query.setOrderDirection("asc");

        PageResult<IamRoleBO> page = roleService.page(query);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).extracting(IamRoleBO::id)
                .containsExactly(first.id(), second.id());
        assertThat(page.items().getFirst().permissionIds())
                .containsExactlyElementsOf(permissionIds.stream().sorted().toList());
        assertThat(page.items().get(1).permissionIds()).isEmpty();
    }

    @Test
    void duplicateCodeReturnsBusinessConflictAndKeepsOriginalRow() {
        IamRoleBO original = roleService.create(
                new IamRoleCreateVO("ITROLE_DUPLICATE", "原角色", null));

        assertApiError(
                () -> roleService.create(
                        new IamRoleCreateVO("ITROLE_DUPLICATE", "重复角色", null)),
                HttpStatus.CONFLICT,
                "ROLE_CODE_CONFLICT");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_role WHERE code = 'ITROLE_DUPLICATE'", Integer.class))
                .isEqualTo(1);
        assertThat(roleService.getById(original.id()).name()).isEqualTo("原角色");
    }

    @Test
    void missingRoleReturnsNotFoundForReadUpdateAndDelete() {
        long missingRoleId = Long.MAX_VALUE;

        assertApiError(() -> roleService.getById(missingRoleId),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");
        assertApiError(() -> roleService.update(
                        missingRoleId, new IamRoleUpdateVO("不存在", null)),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");
        assertApiError(() -> roleService.delete(missingRoleId),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");
    }

    @Test
    void systemAdminCannotBeDeletedAndItsDatabaseRowRemains() {
        Long systemAdminId = jdbc.queryForObject(
                "SELECT id FROM iam_role WHERE code = 'SYSTEM_ADMIN'", Long.class);

        assertApiError(() -> roleService.delete(systemAdminId),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_role WHERE id = ?", Integer.class, systemAdminId))
                .isEqualTo(1);
    }

    @Test
    void roleAssignedToUserCannotBeDeleted() {
        IamRoleBO role = roleService.create(
                new IamRoleCreateVO("ITROLE_USER_REF", "用户引用角色", null));
        long userId = insertUser("role-it-user-ref");
        jdbc.update("""
                INSERT INTO iam_user_role (user_id, role_id, granted_by, granted_at)
                VALUES (?, ?, NULL, CURRENT_TIMESTAMP(3))
                """, userId, role.id());

        assertApiError(() -> roleService.delete(role.id()),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_role WHERE id = ?", Integer.class, role.id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_user_role WHERE user_id = ? AND role_id = ?",
                Integer.class, userId, role.id()))
                .isEqualTo(1);
    }

    @Test
    void roleGrantedPermissionCannotBeDeleted() {
        IamRoleBO role = roleService.create(
                new IamRoleCreateVO("ITROLE_PERMISSION_REF", "权限引用角色", null));
        Long permissionId = jdbc.queryForObject(
                "SELECT id FROM iam_permission WHERE code = 'TICKET_CREATE'", Long.class);
        jdbc.update("""
                INSERT INTO iam_role_permission (role_id, permission_id, granted_by, granted_at)
                VALUES (?, ?, NULL, CURRENT_TIMESTAMP(6))
                """, role.id(), permissionId);

        assertApiError(() -> roleService.delete(role.id()),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_role WHERE id = ?", Integer.class, role.id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_role_permission WHERE role_id = ? AND permission_id = ?",
                Integer.class, role.id(), permissionId))
                .isEqualTo(1);
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

    private void assertApiError(
            ThrowingCallable invocation, HttpStatus expectedStatus, String expectedCode) {
        ApiException exception = catchThrowableOfType(invocation, ApiException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.status()).isEqualTo(expectedStatus);
        assertThat(exception.code()).isEqualTo(expectedCode);
    }
}
