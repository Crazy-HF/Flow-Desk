package com.flowdesk.iam.application.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.domain.IamPermission;
import com.flowdesk.iam.domain.IamRole;
import com.flowdesk.iam.domain.IamRolePermission;
import com.flowdesk.iam.domain.IamUserRole;
import com.flowdesk.iam.application.result.RolePermissionResult;
import com.flowdesk.iam.application.command.GrantRolePermissionsCommand;
import com.flowdesk.iam.application.query.RolePermissionQuery;
import com.flowdesk.iam.application.command.RevokeRolePermissionsCommand;
import com.flowdesk.iam.mapper.IamPermissionMapper;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.mapper.IamRolePermissionMapper;
import com.flowdesk.iam.mapper.IamUserRoleMapper;
import com.flowdesk.iam.application.port.CurrentOperatorPort;
import com.flowdesk.iam.application.port.SessionRevocationPort;
import com.flowdesk.support.MybatisPlusTestMetadata;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 角色权限授权服务的单元测试（`TASK-058` 第三片）。
 *
 * <p>覆盖分页白名单、批量增量授予、幂等、审计两列、按角色范围的会话撤销（用户 ID 升序、每批一次）、
 * `SYSTEM_ADMIN × RBAC_MANAGE` 保护规则，以及锁顺序与锁等待超时的错误映射。真实 MySQL 行为由
 * {@code IamRolePermissionServiceIT} 负责。</p>
 */
class IamRolePermissionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-22T03:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long ROLE_ID = 5L;
    private static final long OPERATOR_ID = 3L;
    private static final long PERMISSION_VIEW = 10L;
    private static final long PERMISSION_CREATE = 11L;
    private static final long USER_A = 21L;
    private static final long USER_B = 22L;

    private final IamUserRoleMapper userRoleMapper = mock(IamUserRoleMapper.class);
    private final IamRolePermissionMapper rolePermissionMapper =
            mock(IamRolePermissionMapper.class);
    private final IamRoleMapper roleMapper = mock(IamRoleMapper.class);
    private final IamPermissionMapper permissionMapper =
            mock(IamPermissionMapper.class);
    private final CurrentOperatorPort currentOperatorPort = mock(CurrentOperatorPort.class);
    private final SessionRevocationPort sessionRevocationPort = mock(SessionRevocationPort.class);

    private final IamRolePermissionServiceImpl service = new IamRolePermissionServiceImpl(
            CLOCK, currentOperatorPort, sessionRevocationPort,
            userRoleMapper, rolePermissionMapper, roleMapper, permissionMapper);

    @BeforeAll
    static void initializeMybatisPlusMetadata() {
        MybatisPlusTestMetadata.initialize(
                IamRolePermission.class, IamRole.class, IamPermission.class, IamUserRole.class);
    }

    // ---------- 分页 ----------

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void defaultsToRoleIdThenPermissionIdAscending() {
        RolePermissionQuery query = new RolePermissionQuery();
        query.setRoleId(ROLE_ID);
        doAnswer(invocation -> {
            Page<IamRolePermission> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of());
            requestedPage.setTotal(0);
            return requestedPage;
        }).when(rolePermissionMapper).selectPage(any(Page.class), any());

        service.page(query);

        ArgumentCaptor<Page<IamRolePermission>> pageCaptor =
                ArgumentCaptor.forClass((Class) Page.class);
        verify(rolePermissionMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().orders()).satisfiesExactly(
                order -> {
                    assertThat(order.getColumn()).isEqualTo("role_id");
                    assertThat(order.isAsc()).isTrue();
                },
                order -> {
                    assertThat(order.getColumn()).isEqualTo("permission_id");
                    assertThat(order.isAsc()).isTrue();
                });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void appliesWhitelistedOrderWithoutExtraTieBreaker() {
        RolePermissionQuery query = new RolePermissionQuery();
        query.setPermissionId(PERMISSION_VIEW);
        query.setOrderBy("permission_id");
        query.setOrderDirection("desc");
        doAnswer(invocation -> {
            Page<IamRolePermission> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of());
            requestedPage.setTotal(0);
            return requestedPage;
        }).when(rolePermissionMapper).selectPage(any(Page.class), any());

        service.page(query);

        ArgumentCaptor<Page<IamRolePermission>> pageCaptor =
                ArgumentCaptor.forClass((Class) Page.class);
        verify(rolePermissionMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().orders()).singleElement().satisfies(order -> {
            assertThat(order.getColumn()).isEqualTo("permission_id");
            assertThat(order.isAsc()).isFalse();
        });
    }

    @Test
    void rejectsOrderFieldOutsideWhitelist() {
        RolePermissionQuery query = new RolePermissionQuery();
        query.setRoleId(ROLE_ID);
        query.setOrderBy("granted_at");

        assertApiException(() -> service.page(query),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(rolePermissionMapper, roleMapper, permissionMapper);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void returnsEmptyPageWithoutLoadingRolesAndPermissions() {
        RolePermissionQuery query = new RolePermissionQuery();
        query.setRoleId(ROLE_ID);
        doAnswer(invocation -> {
            Page<IamRolePermission> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of());
            requestedPage.setTotal(0);
            return requestedPage;
        }).when(rolePermissionMapper).selectPage(any(Page.class), any());

        PageResult<RolePermissionResult> result = service.page(query);

        assertThat(result.items()).isEmpty();
        verifyNoInteractions(roleMapper, permissionMapper);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void loadsRolesAndPermissionsInOneBatchEachAndAssemblesGrantView() {
        RolePermissionQuery query = new RolePermissionQuery();
        query.setRoleId(ROLE_ID);
        doAnswer(invocation -> {
            Page<IamRolePermission> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of(
                    grant(ROLE_ID, PERMISSION_VIEW, OPERATOR_ID),
                    grant(ROLE_ID, PERMISSION_CREATE, null)));
            requestedPage.setTotal(2);
            return requestedPage;
        }).when(rolePermissionMapper).selectPage(any(Page.class), any());
        when(roleMapper.selectBatchIds(any())).thenReturn(List.of(
                role(ROLE_ID, "IT_SUPPORT", "IT 支持")));
        when(permissionMapper.selectBatchIds(any())).thenReturn(List.of(
                permission(PERMISSION_VIEW, "TICKET_VIEW", "查看工单"),
                permission(PERMISSION_CREATE, "TICKET_CREATE", "创建工单")));

        PageResult<RolePermissionResult> result = service.page(query);

        assertThat(result.totalElements()).isEqualTo(2);
        RolePermissionResult first = result.items().getFirst();
        assertThat(first.roleId()).isEqualTo(ROLE_ID);
        assertThat(first.roleCode()).isEqualTo("IT_SUPPORT");
        assertThat(first.permissionId()).isEqualTo(PERMISSION_VIEW);
        assertThat(first.permissionCode()).isEqualTo("TICKET_VIEW");
        assertThat(first.permissionName()).isEqualTo("查看工单");
        assertThat(first.grantedBy()).isEqualTo(OPERATOR_ID);
        assertThat(first.grantedAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThat(result.items().get(1).grantedBy()).isNull();
        verify(roleMapper).selectBatchIds(any());
        verify(permissionMapper).selectBatchIds(any());
    }

    // ---------- 批量增量授予 ----------

    @Test
    void grantsOnlyMissingPermissionsWritesAuditColumnsAndRevokesRoleUsersInUserIdOrder() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "IT_SUPPORT", "IT 支持"));
        when(permissionMapper.selectByIdsForUpdate(List.of(PERMISSION_VIEW, PERMISSION_CREATE)))
                .thenReturn(List.of(
                        permission(PERMISSION_VIEW, "TICKET_VIEW", "查看工单"),
                        permission(PERMISSION_CREATE, "TICKET_CREATE", "创建工单")));
        when(userRoleMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of(
                userRole(USER_A, ROLE_ID), userRole(USER_B, ROLE_ID)));
        when(rolePermissionMapper.selectByRoleIdForUpdate(ROLE_ID))
                .thenReturn(List.of(grant(ROLE_ID, PERMISSION_VIEW, null)));
        when(currentOperatorPort.currentUserId()).thenReturn(OPERATOR_ID);

        List<RolePermissionResult> result = service.grant(
                new GrantRolePermissionsCommand(
                        ROLE_ID, List.of(PERMISSION_CREATE, PERMISSION_VIEW)));

        assertThat(result).extracting(RolePermissionResult::permissionId)
                .containsExactly(PERMISSION_VIEW, PERMISSION_CREATE);
        assertThat(result.getFirst().grantedBy()).isNull();
        assertThat(result.get(1).permissionCode()).isEqualTo("TICKET_CREATE");

        ArgumentCaptor<IamRolePermission> inserted =
                ArgumentCaptor.forClass(IamRolePermission.class);
        verify(rolePermissionMapper, times(1)).insert(inserted.capture());
        assertThat(inserted.getValue().getRoleId()).isEqualTo(ROLE_ID);
        assertThat(inserted.getValue().getPermissionId()).isEqualTo(PERMISSION_CREATE);
        assertThat(inserted.getValue().getGrantedBy()).isEqualTo(OPERATOR_ID);
        assertThat(inserted.getValue().getGrantedAt())
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

        InOrder revocations = inOrder(sessionRevocationPort);
        revocations.verify(sessionRevocationPort).revokeAll(USER_A);
        revocations.verify(sessionRevocationPort).revokeAll(USER_B);

        InOrder order = inOrder(roleMapper, permissionMapper, userRoleMapper,
                rolePermissionMapper, sessionRevocationPort);
        order.verify(roleMapper).selectByIdForUpdate(ROLE_ID);
        order.verify(permissionMapper)
                .selectByIdsForUpdate(List.of(PERMISSION_VIEW, PERMISSION_CREATE));
        order.verify(userRoleMapper).selectByRoleIdForUpdate(ROLE_ID);
        order.verify(rolePermissionMapper).selectByRoleIdForUpdate(ROLE_ID);
        order.verify(sessionRevocationPort).revokeAll(USER_A);
        order.verify(rolePermissionMapper).insert(any(IamRolePermission.class));
    }

    @Test
    void repeatedGrantOfExistingPermissionsIsIdempotentAndDoesNotRevokeSessions() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "IT_SUPPORT", "IT 支持"));
        when(permissionMapper.selectByIdsForUpdate(List.of(PERMISSION_VIEW)))
                .thenReturn(List.of(permission(PERMISSION_VIEW, "TICKET_VIEW", "查看工单")));
        when(userRoleMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of(
                userRole(USER_A, ROLE_ID), userRole(USER_B, ROLE_ID)));
        when(rolePermissionMapper.selectByRoleIdForUpdate(ROLE_ID))
                .thenReturn(List.of(grant(ROLE_ID, PERMISSION_VIEW, OPERATOR_ID)));

        List<RolePermissionResult> result = service.grant(
                new GrantRolePermissionsCommand(ROLE_ID, List.of(PERMISSION_VIEW)));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().grantedBy()).isEqualTo(OPERATOR_ID);
        verify(rolePermissionMapper, never()).insert(any(IamRolePermission.class));
        verifyNoInteractions(currentOperatorPort);
        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void deduplicatesAndSortsPermissionIdsBeforeLocking() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "IT_SUPPORT", "IT 支持"));
        when(permissionMapper.selectByIdsForUpdate(List.of(PERMISSION_VIEW, PERMISSION_CREATE)))
                .thenReturn(List.of(
                        permission(PERMISSION_VIEW, "TICKET_VIEW", "查看工单"),
                        permission(PERMISSION_CREATE, "TICKET_CREATE", "创建工单")));
        when(userRoleMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of());
        when(rolePermissionMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of(
                grant(ROLE_ID, PERMISSION_VIEW, null),
                grant(ROLE_ID, PERMISSION_CREATE, null)));

        service.grant(new GrantRolePermissionsCommand(
                ROLE_ID, List.of(PERMISSION_CREATE, PERMISSION_VIEW, PERMISSION_CREATE)));

        verify(permissionMapper)
                .selectByIdsForUpdate(List.of(PERMISSION_VIEW, PERMISSION_CREATE));
    }

    @Test
    void rejectsMissingRoleBeforeLockingPermissions() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID)).thenReturn(null);

        assertApiException(() -> service.grant(
                        new GrantRolePermissionsCommand(ROLE_ID, List.of(PERMISSION_VIEW))),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");

        verifyNoInteractions(permissionMapper, userRoleMapper, sessionRevocationPort);
    }

    @Test
    void rejectsMissingPermissionBeforeAnyWriteOrRevocation() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "IT_SUPPORT", "IT 支持"));
        when(permissionMapper.selectByIdsForUpdate(List.of(PERMISSION_VIEW, PERMISSION_CREATE)))
                .thenReturn(List.of(permission(PERMISSION_VIEW, "TICKET_VIEW", "查看工单")));

        assertApiException(() -> service.grant(new GrantRolePermissionsCommand(
                        ROLE_ID, List.of(PERMISSION_VIEW, PERMISSION_CREATE))),
                HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND");

        verifyNoInteractions(userRoleMapper, sessionRevocationPort);
        verify(rolePermissionMapper, never()).insert(any(IamRolePermission.class));
    }

    @Test
    void grantLockWaitFailureIsConvertedToRbacConflict() {
        doThrow(new PessimisticLockingFailureException("lock wait timeout"))
                .when(roleMapper).selectByIdForUpdate(ROLE_ID);

        assertApiException(() -> service.grant(
                        new GrantRolePermissionsCommand(ROLE_ID, List.of(PERMISSION_VIEW))),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verifyNoInteractions(sessionRevocationPort);
    }

    // ---------- 单条撤销 ----------

    @Test
    void revokesRolePermissionAfterLockingRolePermissionAndUserSnapshot() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "IT_SUPPORT", "IT 支持"));
        when(permissionMapper.selectByIdForUpdate(PERMISSION_VIEW))
                .thenReturn(permission(PERMISSION_VIEW, "TICKET_VIEW", "查看工单"));
        when(userRoleMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of(
                userRole(USER_A, ROLE_ID), userRole(USER_B, ROLE_ID)));
        when(rolePermissionMapper.selectByKeyForUpdate(ROLE_ID, PERMISSION_VIEW))
                .thenReturn(grant(ROLE_ID, PERMISSION_VIEW, OPERATOR_ID));

        service.revoke(ROLE_ID, PERMISSION_VIEW);

        InOrder revocations = inOrder(sessionRevocationPort);
        revocations.verify(sessionRevocationPort).revokeAll(USER_A);
        revocations.verify(sessionRevocationPort).revokeAll(USER_B);

        InOrder order = inOrder(roleMapper, permissionMapper, userRoleMapper,
                rolePermissionMapper, sessionRevocationPort);
        order.verify(roleMapper).selectByIdForUpdate(ROLE_ID);
        order.verify(permissionMapper).selectByIdForUpdate(PERMISSION_VIEW);
        order.verify(userRoleMapper).selectByRoleIdForUpdate(ROLE_ID);
        order.verify(rolePermissionMapper).selectByKeyForUpdate(ROLE_ID, PERMISSION_VIEW);
        order.verify(sessionRevocationPort).revokeAll(USER_A);
        order.verify(rolePermissionMapper).delete(any());
    }

    @Test
    void rejectsRevokeWhenGrantDoesNotExist() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "IT_SUPPORT", "IT 支持"));
        when(permissionMapper.selectByIdForUpdate(PERMISSION_VIEW))
                .thenReturn(permission(PERMISSION_VIEW, "TICKET_VIEW", "查看工单"));
        when(userRoleMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of());
        when(rolePermissionMapper.selectByKeyForUpdate(ROLE_ID, PERMISSION_VIEW))
                .thenReturn(null);

        assertApiException(() -> service.revoke(ROLE_ID, PERMISSION_VIEW),
                HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND");

        verifyNoInteractions(sessionRevocationPort);
        verify(rolePermissionMapper, never()).delete(any());
    }

    @Test
    void rejectsRevokeWhenRoleIsMissing() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID)).thenReturn(null);

        assertApiException(() -> service.revoke(ROLE_ID, PERMISSION_VIEW),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");

        verifyNoInteractions(permissionMapper, userRoleMapper, sessionRevocationPort);
    }

    @Test
    void rejectsRevokeWhenPermissionIsMissing() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "IT_SUPPORT", "IT 支持"));
        when(permissionMapper.selectByIdForUpdate(PERMISSION_VIEW)).thenReturn(null);

        assertApiException(() -> service.revoke(ROLE_ID, PERMISSION_VIEW),
                HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND");

        verifyNoInteractions(userRoleMapper, sessionRevocationPort);
    }

    @Test
    void protectsSystemAdminRbacManageGrantFromRevocation() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "SYSTEM_ADMIN", "系统管理员"));
        when(permissionMapper.selectByIdForUpdate(PERMISSION_VIEW))
                .thenReturn(permission(PERMISSION_VIEW, "RBAC_MANAGE", "RBAC 管理"));
        when(userRoleMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of(
                userRole(USER_A, ROLE_ID)));
        when(rolePermissionMapper.selectByKeyForUpdate(ROLE_ID, PERMISSION_VIEW))
                .thenReturn(grant(ROLE_ID, PERMISSION_VIEW, null));

        assertApiException(() -> service.revoke(ROLE_ID, PERMISSION_VIEW),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verifyNoInteractions(sessionRevocationPort);
        verify(rolePermissionMapper, never()).delete(any());
    }

    @Test
    void allowsRevokingOtherPermissionsFromSystemAdminRole() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "SYSTEM_ADMIN", "系统管理员"));
        when(permissionMapper.selectByIdForUpdate(PERMISSION_VIEW))
                .thenReturn(permission(PERMISSION_VIEW, "TICKET_VIEW", "查看工单"));
        when(userRoleMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of(
                userRole(USER_A, ROLE_ID)));
        when(rolePermissionMapper.selectByKeyForUpdate(ROLE_ID, PERMISSION_VIEW))
                .thenReturn(grant(ROLE_ID, PERMISSION_VIEW, null));

        service.revoke(ROLE_ID, PERMISSION_VIEW);

        verify(sessionRevocationPort).revokeAll(USER_A);
        verify(rolePermissionMapper).delete(any());
    }

    @Test
    void revokeLockWaitFailureIsConvertedToRbacConflict() {
        doThrow(new PessimisticLockingFailureException("deadlock"))
                .when(roleMapper).selectByIdForUpdate(ROLE_ID);

        assertApiException(() -> service.revoke(ROLE_ID, PERMISSION_VIEW),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verify(rolePermissionMapper, never()).delete(any());
    }

    @Test
    void writeMethodsDeclareTransactionBoundaries() throws NoSuchMethodException {
        assertThat(IamRolePermissionServiceImpl.class
                .getMethod("grant", GrantRolePermissionsCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamRolePermissionServiceImpl.class
                .getMethod("revoke", long.class, long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamRolePermissionServiceImpl.class
                .getMethod("revokeBatch", RevokeRolePermissionsCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamRolePermissionServiceImpl.class
                .getMethod("clearAll", long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    // ---------- 批量撤销（POST /role-permissions/actions/revoke） ----------

    @Test
    void revokesRequestedPermissionsInOneBatchAndRevokesRoleUsersOnce() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "IT_SUPPORT", "IT 支持"));
        when(permissionMapper.selectByIdsForUpdate(List.of(PERMISSION_VIEW, PERMISSION_CREATE)))
                .thenReturn(List.of(
                        permission(PERMISSION_VIEW, "TICKET_VIEW", "查看工单"),
                        permission(PERMISSION_CREATE, "TICKET_CREATE", "创建工单")));
        when(userRoleMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of(
                userRole(USER_A, ROLE_ID), userRole(USER_B, ROLE_ID)));
        when(rolePermissionMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of(
                grant(ROLE_ID, PERMISSION_VIEW, null),
                grant(ROLE_ID, PERMISSION_CREATE, null)));
        when(rolePermissionMapper.delete(any())).thenReturn(2);

        service.revokeBatch(new RevokeRolePermissionsCommand(
                ROLE_ID, List.of(PERMISSION_CREATE, PERMISSION_VIEW, PERMISSION_CREATE)));

        InOrder revocations = inOrder(sessionRevocationPort);
        revocations.verify(sessionRevocationPort).revokeAll(USER_A);
        revocations.verify(sessionRevocationPort).revokeAll(USER_B);

        InOrder order = inOrder(roleMapper, permissionMapper, userRoleMapper,
                rolePermissionMapper, sessionRevocationPort);
        order.verify(roleMapper).selectByIdForUpdate(ROLE_ID);
        order.verify(permissionMapper)
                .selectByIdsForUpdate(List.of(PERMISSION_VIEW, PERMISSION_CREATE));
        order.verify(userRoleMapper).selectByRoleIdForUpdate(ROLE_ID);
        order.verify(rolePermissionMapper).selectByRoleIdForUpdate(ROLE_ID);
        order.verify(sessionRevocationPort).revokeAll(USER_A);
        order.verify(rolePermissionMapper).delete(any());
    }

    @Test
    void rejectsBatchRevokeWhenAnyGrantIsMissingWithoutRevocation() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "IT_SUPPORT", "IT 支持"));
        when(permissionMapper.selectByIdsForUpdate(List.of(PERMISSION_VIEW, PERMISSION_CREATE)))
                .thenReturn(List.of(
                        permission(PERMISSION_VIEW, "TICKET_VIEW", "查看工单"),
                        permission(PERMISSION_CREATE, "TICKET_CREATE", "创建工单")));
        when(userRoleMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of(
                userRole(USER_A, ROLE_ID)));
        when(rolePermissionMapper.selectByRoleIdForUpdate(ROLE_ID))
                .thenReturn(List.of(grant(ROLE_ID, PERMISSION_VIEW, null)));

        assertApiException(() -> service.revokeBatch(new RevokeRolePermissionsCommand(
                        ROLE_ID, List.of(PERMISSION_VIEW, PERMISSION_CREATE))),
                HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND");

        verifyNoInteractions(sessionRevocationPort);
        verify(rolePermissionMapper, never()).delete(any());
    }

    @Test
    void rejectsBatchRevokeWhenAnyPermissionIsMissing() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "IT_SUPPORT", "IT 支持"));
        when(permissionMapper.selectByIdsForUpdate(List.of(PERMISSION_VIEW, PERMISSION_CREATE)))
                .thenReturn(List.of(permission(PERMISSION_VIEW, "TICKET_VIEW", "查看工单")));

        assertApiException(() -> service.revokeBatch(new RevokeRolePermissionsCommand(
                        ROLE_ID, List.of(PERMISSION_VIEW, PERMISSION_CREATE))),
                HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND");

        verifyNoInteractions(userRoleMapper, sessionRevocationPort);
    }

    @Test
    void rejectsBatchRevokeWhenRoleIsMissing() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID)).thenReturn(null);

        assertApiException(() -> service.revokeBatch(new RevokeRolePermissionsCommand(
                        ROLE_ID, List.of(PERMISSION_VIEW))),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");

        verifyNoInteractions(permissionMapper, userRoleMapper, sessionRevocationPort);
    }

    @Test
    void protectsSystemAdminRbacManageDuringBatchRevoke() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "SYSTEM_ADMIN", "系统管理员"));
        when(permissionMapper.selectByIdsForUpdate(List.of(PERMISSION_VIEW)))
                .thenReturn(List.of(permission(PERMISSION_VIEW, "RBAC_MANAGE", "RBAC 管理")));
        when(userRoleMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of());
        when(rolePermissionMapper.selectByRoleIdForUpdate(ROLE_ID))
                .thenReturn(List.of(grant(ROLE_ID, PERMISSION_VIEW, null)));

        assertApiException(() -> service.revokeBatch(new RevokeRolePermissionsCommand(
                        ROLE_ID, List.of(PERMISSION_VIEW))),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verifyNoInteractions(sessionRevocationPort);
        verify(rolePermissionMapper, never()).delete(any());
    }

    @Test
    void reportsConflictWhenBatchRevokeDeletedRowCountDiffers() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "IT_SUPPORT", "IT 支持"));
        when(permissionMapper.selectByIdsForUpdate(List.of(PERMISSION_VIEW, PERMISSION_CREATE)))
                .thenReturn(List.of(
                        permission(PERMISSION_VIEW, "TICKET_VIEW", "查看工单"),
                        permission(PERMISSION_CREATE, "TICKET_CREATE", "创建工单")));
        when(userRoleMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of());
        when(rolePermissionMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of(
                grant(ROLE_ID, PERMISSION_VIEW, null),
                grant(ROLE_ID, PERMISSION_CREATE, null)));
        when(rolePermissionMapper.delete(any())).thenReturn(1);

        assertApiException(() -> service.revokeBatch(new RevokeRolePermissionsCommand(
                        ROLE_ID, List.of(PERMISSION_VIEW, PERMISSION_CREATE))),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");
    }

    @Test
    void convertsBatchRevokeLockFailureToRbacConflict() {
        doThrow(new PessimisticLockingFailureException("deadlock"))
                .when(roleMapper).selectByIdForUpdate(ROLE_ID);

        assertApiException(() -> service.revokeBatch(new RevokeRolePermissionsCommand(
                        ROLE_ID, List.of(PERMISSION_VIEW))),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");
    }

    // ---------- 清空全部权限（DELETE /role-permissions/roles/{roleId}） ----------

    @Test
    void clearAllRemovesEveryPermissionAndRevokesRoleUsersOnce() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "IT_SUPPORT", "IT 支持"));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(
                grant(ROLE_ID, PERMISSION_VIEW, null),
                grant(ROLE_ID, PERMISSION_CREATE, null)));
        when(permissionMapper.selectByIdsForUpdate(List.of(PERMISSION_VIEW, PERMISSION_CREATE)))
                .thenReturn(List.of(
                        permission(PERMISSION_VIEW, "TICKET_VIEW", "查看工单"),
                        permission(PERMISSION_CREATE, "TICKET_CREATE", "创建工单")));
        when(userRoleMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of(
                userRole(USER_A, ROLE_ID), userRole(USER_B, ROLE_ID)));
        when(rolePermissionMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of(
                grant(ROLE_ID, PERMISSION_VIEW, null),
                grant(ROLE_ID, PERMISSION_CREATE, null)));
        when(rolePermissionMapper.delete(any())).thenReturn(2);

        service.clearAll(ROLE_ID);

        InOrder revocations = inOrder(sessionRevocationPort);
        revocations.verify(sessionRevocationPort).revokeAll(USER_A);
        revocations.verify(sessionRevocationPort).revokeAll(USER_B);

        InOrder order = inOrder(roleMapper, permissionMapper, sessionRevocationPort);
        order.verify(roleMapper).selectByIdForUpdate(ROLE_ID);
        order.verify(permissionMapper)
                .selectByIdsForUpdate(List.of(PERMISSION_VIEW, PERMISSION_CREATE));
        order.verify(sessionRevocationPort).revokeAll(USER_A);
    }

    @Test
    void clearAllIsIdempotentWhenRoleHasNoPermissions() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "IT_SUPPORT", "IT 支持"));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of());

        service.clearAll(ROLE_ID);

        verifyNoInteractions(permissionMapper, userRoleMapper, sessionRevocationPort);
        verify(rolePermissionMapper, never()).delete(any());
    }

    @Test
    void clearAllRejectsMissingRole() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID)).thenReturn(null);

        assertApiException(() -> service.clearAll(ROLE_ID),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");

        verifyNoInteractions(rolePermissionMapper, permissionMapper, sessionRevocationPort);
    }

    @Test
    void clearAllProtectsSystemAdminRbacManage() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "SYSTEM_ADMIN", "系统管理员"));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(
                grant(ROLE_ID, PERMISSION_VIEW, null)));
        when(permissionMapper.selectByIdsForUpdate(List.of(PERMISSION_VIEW)))
                .thenReturn(List.of(permission(PERMISSION_VIEW, "RBAC_MANAGE", "RBAC 管理")));

        assertApiException(() -> service.clearAll(ROLE_ID),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verifyNoInteractions(userRoleMapper, sessionRevocationPort);
        verify(rolePermissionMapper, never()).delete(any());
    }

    @Test
    void clearAllReportsConflictWhenPermissionsChangedConcurrently() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "IT_SUPPORT", "IT 支持"));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(
                grant(ROLE_ID, PERMISSION_VIEW, null),
                grant(ROLE_ID, PERMISSION_CREATE, null)));
        when(permissionMapper.selectByIdsForUpdate(List.of(PERMISSION_VIEW, PERMISSION_CREATE)))
                .thenReturn(List.of(
                        permission(PERMISSION_VIEW, "TICKET_VIEW", "查看工单"),
                        permission(PERMISSION_CREATE, "TICKET_CREATE", "创建工单")));
        when(rolePermissionMapper.selectByRoleIdForUpdate(ROLE_ID))
                .thenReturn(List.of(grant(ROLE_ID, PERMISSION_VIEW, null)));

        assertApiException(() -> service.clearAll(ROLE_ID),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verifyNoInteractions(sessionRevocationPort);
        verify(rolePermissionMapper, never()).delete(any());
    }

    @Test
    void clearAllReportsConflictWhenDeletedRowCountDiffers() {
        when(roleMapper.selectByIdForUpdate(ROLE_ID))
                .thenReturn(role(ROLE_ID, "IT_SUPPORT", "IT 支持"));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(
                grant(ROLE_ID, PERMISSION_VIEW, null),
                grant(ROLE_ID, PERMISSION_CREATE, null)));
        when(permissionMapper.selectByIdsForUpdate(List.of(PERMISSION_VIEW, PERMISSION_CREATE)))
                .thenReturn(List.of(
                        permission(PERMISSION_VIEW, "TICKET_VIEW", "查看工单"),
                        permission(PERMISSION_CREATE, "TICKET_CREATE", "创建工单")));
        when(userRoleMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of());
        when(rolePermissionMapper.selectByRoleIdForUpdate(ROLE_ID)).thenReturn(List.of(
                grant(ROLE_ID, PERMISSION_VIEW, null),
                grant(ROLE_ID, PERMISSION_CREATE, null)));
        when(rolePermissionMapper.delete(any())).thenReturn(1);

        assertApiException(() -> service.clearAll(ROLE_ID),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");
    }

    // ---------- 辅助 ----------

    private static IamRolePermission grant(long roleId, long permissionId, Long grantedBy) {
        IamRolePermission grant = new IamRolePermission();
        grant.setRoleId(roleId);
        grant.setPermissionId(permissionId);
        grant.setGrantedBy(grantedBy);
        grant.setGrantedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return grant;
    }

    private static IamUserRole userRole(long userId, long roleId) {
        IamUserRole userRole = new IamUserRole();
        userRole.setUserId(userId);
        userRole.setRoleId(roleId);
        userRole.setGrantedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return userRole;
    }

    private static IamRole role(long id, String code, String name) {
        IamRole role = new IamRole();
        role.setId(id);
        role.setCode(code);
        role.setName(name);
        role.setCreatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return role;
    }

    private static IamPermission permission(long id, String code, String name) {
        IamPermission permission = new IamPermission();
        permission.setId(id);
        permission.setCode(code);
        permission.setName(name);
        permission.setCreatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return permission;
    }

    private static void assertApiException(
            ThrowingCallable action, HttpStatus status, String code) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(status);
                    assertThat(exception.code()).isEqualTo(code);
                });
    }
}
