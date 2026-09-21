package com.flowdesk.iam.service.impl;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.domain.bo.IamPermissionBO;
import com.flowdesk.iam.domain.vo.IamPermissionCreateVO;
import com.flowdesk.iam.domain.vo.IamPermissionQueryVO;
import com.flowdesk.iam.domain.vo.IamPermissionUpdateVO;
import com.flowdesk.iam.service.IamPermissionService;
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
 * 权限服务的真实 MySQL 集成测试。
 *
 * <p>这里不使用测试级 {@code @Transactional}：更新、删除中的 {@code SELECT ... FOR UPDATE}
 * 必须依赖服务自身声明的事务，避免测试外层事务掩盖生产事务边界缺失。每个用例结束后显式清理
 * {@code ITPERMISSION} 前缀数据，内置 RBAC 基线保持不变。</p>
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration")
class IamPermissionServiceIT {

    private static final String MYSQL_IMAGE = "mysql:8.4.11";

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MYSQL_IMAGE)
            .withDatabaseName("flowdesk_permission_it")
            .withUsername("flowdesk")
            .withPassword("flowdesk");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private IamPermissionService permissionService;

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
                JOIN iam_permission permission
                  ON permission.id = role_permission.permission_id
                WHERE permission.code LIKE 'ITPERMISSION%'
                """);
        jdbc.update("DELETE FROM iam_permission WHERE code LIKE 'ITPERMISSION%'");
    }

    @Test
    void createsReadsUpdatesAndDeletesUnreferencedCustomPermission() {
        IamPermissionBO created = permissionService.create(
                new IamPermissionCreateVO(
                        "ITPERMISSION_CRUD", "集成测试权限", "创建说明"));

        assertThat(created.id()).isPositive();
        assertThat(created.code()).isEqualTo("ITPERMISSION_CRUD");
        assertThat(created.name()).isEqualTo("集成测试权限");
        assertThat(created.description()).isEqualTo("创建说明");
        assertThat(created.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(created.roleIds()).isEmpty();

        IamPermissionBO found = permissionService.getById(created.id());
        assertThat(found.code()).isEqualTo("ITPERMISSION_CRUD");
        assertThat(found.roleIds()).isEmpty();

        IamPermissionBO updated = permissionService.update(
                created.id(), new IamPermissionUpdateVO("已更新权限", null));
        assertThat(updated.code()).isEqualTo("ITPERMISSION_CRUD");
        assertThat(updated.name()).isEqualTo("已更新权限");
        assertThat(updated.description()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT code FROM iam_permission WHERE id = ?",
                String.class,
                created.id())).isEqualTo("ITPERMISSION_CRUD");

        permissionService.delete(created.id());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_permission WHERE id = ?",
                Integer.class,
                created.id())).isZero();
        assertApiError(() -> permissionService.getById(created.id()),
                HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND");
    }

    @Test
    void pagesRealRowsWithStableOrderAndOrderedRoleIds() {
        IamPermissionBO first = permissionService.create(
                new IamPermissionCreateVO(
                        "ITPERMISSION_PAGE_A", "同名分页权限", null));
        IamPermissionBO second = permissionService.create(
                new IamPermissionCreateVO(
                        "ITPERMISSION_PAGE_B", "同名分页权限", null));
        permissionService.create(new IamPermissionCreateVO(
                "ITPERMISSION_PAGE_C", "同名分页权限", null));

        List<Long> roleIds = jdbc.queryForList(
                "SELECT id FROM iam_role ORDER BY id DESC LIMIT 2", Long.class);
        jdbc.update("""
                INSERT INTO iam_role_permission (
                    role_id, permission_id, granted_by, granted_at
                ) VALUES (?, ?, NULL, CURRENT_TIMESTAMP(6)),
                         (?, ?, NULL, CURRENT_TIMESTAMP(6))
                """,
                roleIds.get(0), first.id(), roleIds.get(1), first.id());

        IamPermissionQueryVO query = new IamPermissionQueryVO();
        query.setPageNo(1);
        query.setPageSize(2);
        query.setKeyword("ITPERMISSION_PAGE");
        query.setOrderBy("name");
        query.setOrderDirection("asc");

        PageResult<IamPermissionBO> page = permissionService.page(query);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items()).extracting(IamPermissionBO::id)
                .containsExactly(first.id(), second.id());
        assertThat(page.items().getFirst().roleIds())
                .containsExactlyElementsOf(roleIds.stream().sorted().toList());
        assertThat(page.items().get(1).roleIds()).isEmpty();
    }

    @Test
    void duplicateCodeReturnsBusinessConflictAndKeepsOriginalRow() {
        IamPermissionBO original = permissionService.create(
                new IamPermissionCreateVO(
                        "ITPERMISSION_DUPLICATE", "原权限", null));

        assertApiError(
                () -> permissionService.create(new IamPermissionCreateVO(
                        "ITPERMISSION_DUPLICATE", "重复权限", null)),
                HttpStatus.CONFLICT,
                "PERMISSION_CODE_CONFLICT");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_permission "
                        + "WHERE code = 'ITPERMISSION_DUPLICATE'",
                Integer.class)).isEqualTo(1);
        assertThat(permissionService.getById(original.id()).name())
                .isEqualTo("原权限");
    }

    @Test
    void missingPermissionReturnsNotFoundForReadUpdateAndDelete() {
        long missingPermissionId = Long.MAX_VALUE;

        assertApiError(() -> permissionService.getById(missingPermissionId),
                HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND");
        assertApiError(() -> permissionService.update(
                        missingPermissionId,
                        new IamPermissionUpdateVO("不存在", null)),
                HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND");
        assertApiError(() -> permissionService.delete(missingPermissionId),
                HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND");
    }

    @Test
    void rbacManageCannotBeDeletedAndItsDatabaseRowRemains() {
        Long rbacManageId = jdbc.queryForObject(
                "SELECT id FROM iam_permission WHERE code = 'RBAC_MANAGE'",
                Long.class);

        assertApiError(() -> permissionService.delete(rbacManageId),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_permission WHERE id = ?",
                Integer.class,
                rbacManageId)).isEqualTo(1);
    }

    @Test
    void permissionGrantedToRoleCannotBeDeleted() {
        IamPermissionBO permission = permissionService.create(
                new IamPermissionCreateVO(
                        "ITPERMISSION_ROLE_REF", "角色引用权限", null));
        Long roleId = jdbc.queryForObject(
                "SELECT id FROM iam_role WHERE code = 'SYSTEM_ADMIN'",
                Long.class);
        jdbc.update("""
                INSERT INTO iam_role_permission (
                    role_id, permission_id, granted_by, granted_at
                ) VALUES (?, ?, NULL, CURRENT_TIMESTAMP(6))
                """, roleId, permission.id());

        assertApiError(() -> permissionService.delete(permission.id()),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_permission WHERE id = ?",
                Integer.class,
                permission.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM iam_role_permission "
                        + "WHERE role_id = ? AND permission_id = ?",
                Integer.class,
                roleId,
                permission.id())).isEqualTo(1);
    }

    private void assertApiError(
            ThrowingCallable invocation,
            HttpStatus expectedStatus,
            String expectedCode) {
        ApiException exception = catchThrowableOfType(invocation, ApiException.class);
        assertThat(exception).isNotNull();
        assertThat(exception.status()).isEqualTo(expectedStatus);
        assertThat(exception.code()).isEqualTo(expectedCode);
    }
}
