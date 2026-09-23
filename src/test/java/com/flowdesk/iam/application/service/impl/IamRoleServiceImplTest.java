package com.flowdesk.iam.application.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.domain.IamPermission;
import com.flowdesk.iam.domain.IamRole;
import com.flowdesk.iam.domain.IamRolePermission;
import com.flowdesk.iam.domain.IamUserRole;
import com.flowdesk.iam.application.result.RoleResult;
import com.flowdesk.iam.application.command.CreateRoleCommand;
import com.flowdesk.iam.application.query.RoleQuery;
import com.flowdesk.iam.application.command.UpdateRoleCommand;
import com.flowdesk.iam.mapper.IamPermissionMapper;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.mapper.IamRolePermissionMapper;
import com.flowdesk.iam.mapper.IamUserRoleMapper;
import com.flowdesk.iam.application.port.CurrentOperatorPort;
import com.flowdesk.support.MybatisPlusTestMetadata;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IamRoleServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-21T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long OPERATOR_ID = 3L;

    private final IamRoleMapper roleMapper = mock(IamRoleMapper.class);
    private final IamUserRoleMapper userRoleMapper = mock(IamUserRoleMapper.class);
    private final IamRolePermissionMapper rolePermissionMapper = mock(IamRolePermissionMapper.class);
    private final IamPermissionMapper permissionMapper = mock(IamPermissionMapper.class);
    private final CurrentOperatorPort currentOperatorPort = mock(CurrentOperatorPort.class);
    private final IamRoleServiceImpl service = new IamRoleServiceImpl(
            CLOCK, currentOperatorPort, permissionMapper,
            roleMapper, userRoleMapper, rolePermissionMapper);

    @BeforeAll
    static void initializeMybatisPlusMetadata() {
        MybatisPlusTestMetadata.initialize(
                IamRole.class, IamRolePermission.class, IamPermission.class, IamUserRole.class);
    }

    @Test
    void rejectsNonPositiveRoleIdWithoutQueryingDatabase() {
        assertApiException(() -> service.getById(0),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");

        verifyNoInteractions(roleMapper, userRoleMapper, rolePermissionMapper);
    }

    @Test
    void returnsNotFoundWhenRequestedRoleDoesNotExist() {
        when(roleMapper.selectById(99L)).thenReturn(null);

        assertApiException(() -> service.getById(99L),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");

        verifyNoInteractions(userRoleMapper, rolePermissionMapper);
    }

    @Test
    void returnsRoleDetailWithOrderedPermissionIds() {
        IamRole role = role(7L, "AUDITOR", "审计员");
        when(roleMapper.selectById(7L)).thenReturn(role);
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(
                rolePermission(7L, 10L),
                rolePermission(7L, 20L)));

        RoleResult result = service.getById(7L);

        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.code()).isEqualTo("AUDITOR");
        assertThat(result.createdAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThat(result.permissionIds()).containsExactly(10L, 20L);
        verify(roleMapper).selectById(7L);
        verify(rolePermissionMapper).selectList(any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void usesIdAscendingAsDefaultPageOrder() {
        RoleQuery query = new RoleQuery();
        IamRole role = role(7L, "AUDITOR", "审计员");
        doAnswer(invocation -> {
            Page<IamRole> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of(role));
            requestedPage.setTotal(1);
            return requestedPage;
        }).when(roleMapper).selectPage(any(Page.class), any());
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of());

        service.page(query);

        ArgumentCaptor<Page<IamRole>> pageCaptor =
                ArgumentCaptor.forClass((Class) Page.class);
        verify(roleMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().orders()).singleElement().satisfies(order -> {
            assertThat(order.getColumn()).isEqualTo("id");
            assertThat(order.isAsc()).isTrue();
        });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void appendsIdAscendingAsStableTieBreakerForCustomPageOrder() {
        RoleQuery query = new RoleQuery();
        query.setOrderBy("name");
        query.setOrderDirection("desc");
        doAnswer(invocation -> {
            Page<IamRole> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of());
            requestedPage.setTotal(0);
            return requestedPage;
        }).when(roleMapper).selectPage(any(Page.class), any());

        service.page(query);

        ArgumentCaptor<Page<IamRole>> pageCaptor =
                ArgumentCaptor.forClass((Class) Page.class);
        verify(roleMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().orders()).satisfiesExactly(
                order -> {
                    assertThat(order.getColumn()).isEqualTo("name");
                    assertThat(order.isAsc()).isFalse();
                },
                order -> {
                    assertThat(order.getColumn()).isEqualTo("id");
                    assertThat(order.isAsc()).isTrue();
                });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void loadsPagePermissionsInOneBatchAndUsesEmptyListForRolesWithoutPermissions() {
        RoleQuery query = new RoleQuery();
        query.setOrderBy("code");
        query.setOrderDirection("desc");
        IamRole auditor = role(7L, "AUDITOR", "审计员");
        IamRole operator = role(8L, "OPERATOR", "操作员");
        doAnswer(invocation -> {
            Page<IamRole> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of(auditor, operator));
            requestedPage.setTotal(2);
            return requestedPage;
        }).when(roleMapper).selectPage(any(Page.class), any());
        when(rolePermissionMapper.selectList(any()))
                .thenReturn(List.of(rolePermission(7L, 10L)));

        PageResult<RoleResult> result = service.page(query);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).permissionIds()).containsExactly(10L);
        assertThat(result.items().get(1).permissionIds()).isEmpty();
        verify(rolePermissionMapper).selectList(any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void returnsEmptyPageWithoutQueryingPermissionRelations() {
        RoleQuery query = new RoleQuery();
        query.setOrderBy("code");
        doAnswer(invocation -> {
            Page<IamRole> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of());
            requestedPage.setTotal(0);
            return requestedPage;
        }).when(roleMapper).selectPage(any(Page.class), any());

        PageResult<RoleResult> result = service.page(query);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isZero();
        verifyNoInteractions(rolePermissionMapper);
    }

    @Test
    void rejectsExistingRoleCodeBeforeInsert() {
        CreateRoleCommand request = new CreateRoleCommand("AUDITOR", "审计员", null, null);
        when(roleMapper.selectCount(any())).thenReturn(1L);

        assertApiException(() -> service.create(request),
                HttpStatus.CONFLICT, "ROLE_CODE_CONFLICT");

        verify(roleMapper, never()).insert(any(IamRole.class));
    }

    @Test
    void createsRoleWithUtcTimeAndReturnsGeneratedId() {
        CreateRoleCommand request = new CreateRoleCommand("AUDITOR", "审计员", "只读审计", null);
        when(roleMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            IamRole inserted = invocation.getArgument(0);
            inserted.setId(7L);
            return 1;
        }).when(roleMapper).insert(any(IamRole.class));

        RoleResult result = service.create(request);

        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.code()).isEqualTo("AUDITOR");
        assertThat(result.name()).isEqualTo("审计员");
        assertThat(result.description()).isEqualTo("只读审计");
        assertThat(result.createdAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThat(result.permissionIds()).isEmpty();
        verifyNoInteractions(permissionMapper, currentOperatorPort);
    }

    @Test
    void convertsConcurrentDuplicateKeyFailureToRoleCodeConflict() {
        CreateRoleCommand request = new CreateRoleCommand("AUDITOR", "审计员", null, null);
        when(roleMapper.selectCount(any())).thenReturn(0L);
        doThrow(new DuplicateKeyException("duplicate"))
                .when(roleMapper).insert(any(IamRole.class));

        assertApiException(() -> service.create(request),
                HttpStatus.CONFLICT, "ROLE_CODE_CONFLICT");
    }

    @Test
    void createsRoleWithPermissionsInOneTransactionAndWritesAuditColumns() {
        CreateRoleCommand request = new CreateRoleCommand(
                "AUDITOR", "审计员", null, List.of(20L, 10L, 20L));
        when(roleMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            IamRole inserted = invocation.getArgument(0);
            inserted.setId(7L);
            return 1;
        }).when(roleMapper).insert(any(IamRole.class));
        when(permissionMapper.selectByIdsForUpdate(List.of(10L, 20L)))
                .thenReturn(List.of(permission(10L), permission(20L)));
        when(currentOperatorPort.currentUserId()).thenReturn(OPERATOR_ID);

        RoleResult result = service.create(request);

        assertThat(result.permissionIds()).containsExactly(10L, 20L);
        verify(permissionMapper).selectByIdsForUpdate(List.of(10L, 20L));

        ArgumentCaptor<IamRolePermission> grants =
                ArgumentCaptor.forClass(IamRolePermission.class);
        verify(rolePermissionMapper, times(2)).insert(grants.capture());
        assertThat(grants.getAllValues())
                .extracting(IamRolePermission::getPermissionId)
                .containsExactly(10L, 20L);
        assertThat(grants.getAllValues())
                .allSatisfy(grant -> {
                    assertThat(grant.getRoleId()).isEqualTo(7L);
                    assertThat(grant.getGrantedBy()).isEqualTo(OPERATOR_ID);
                    assertThat(grant.getGrantedAt())
                            .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
                });
    }

    @Test
    void rejectsCreationWhenAnyRequestedPermissionIsMissing() {
        CreateRoleCommand request = new CreateRoleCommand(
                "AUDITOR", "审计员", null, List.of(10L, 99L));
        when(roleMapper.selectCount(any())).thenReturn(0L);
        when(permissionMapper.selectByIdsForUpdate(List.of(10L, 99L)))
                .thenReturn(List.of(permission(10L)));

        assertApiException(() -> service.create(request),
                HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND");

        verify(rolePermissionMapper, never()).insert(any(IamRolePermission.class));
    }

    @Test
    void convertsPermissionLockFailureDuringCreationToRbacConflict() {
        CreateRoleCommand request = new CreateRoleCommand(
                "AUDITOR", "审计员", null, List.of(10L));
        when(roleMapper.selectCount(any())).thenReturn(0L);
        doThrow(new PessimisticLockingFailureException("lock wait timeout"))
                .when(permissionMapper).selectByIdsForUpdate(List.of(10L));

        assertApiException(() -> service.create(request),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verify(rolePermissionMapper, never()).insert(any(IamRolePermission.class));
    }

    @Test
    void returnsNotFoundWhenLockedRoleDoesNotExistDuringUpdate() {
        when(roleMapper.selectByIdForUpdate(99L)).thenReturn(null);

        assertApiException(
                () -> service.update(99L, new UpdateRoleCommand("新名称", null)),
                HttpStatus.NOT_FOUND,
                "ROLE_NOT_FOUND");

        verify(roleMapper, never()).update(any(), any());
    }

    @Test
    void updatesOnlyMutableRoleDetailsAfterLockingRole() {
        IamRole role = role(7L, "AUDITOR", "旧名称");
        role.setDescription("旧说明");
        when(roleMapper.selectByIdForUpdate(7L)).thenReturn(role);
        when(rolePermissionMapper.selectList(any()))
                .thenReturn(List.of(rolePermission(7L, 10L)));

        RoleResult result = service.update(
                7L, new UpdateRoleCommand("新名称", null));

        assertThat(result.code()).isEqualTo("AUDITOR");
        assertThat(result.name()).isEqualTo("新名称");
        assertThat(result.description()).isNull();
        assertThat(result.permissionIds()).containsExactly(10L);
        InOrder order = inOrder(roleMapper, rolePermissionMapper);
        order.verify(roleMapper).selectByIdForUpdate(7L);
        order.verify(roleMapper).update(isNull(), any());
        order.verify(rolePermissionMapper).selectList(any());
    }

    @Test
    void alwaysProtectsSystemAdminFromDeletion() {
        when(roleMapper.selectByIdForUpdate(1L))
                .thenReturn(role(1L, "SYSTEM_ADMIN", "系统管理员"));

        assertApiException(() -> service.delete(1L),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verifyNoInteractions(userRoleMapper, rolePermissionMapper);
        verify(roleMapper, never()).deleteById(1L);
    }

    @Test
    void returnsNotFoundWhenLockedRoleDoesNotExistDuringDeletion() {
        when(roleMapper.selectByIdForUpdate(99L)).thenReturn(null);

        assertApiException(() -> service.delete(99L),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");

        verifyNoInteractions(userRoleMapper, rolePermissionMapper);
        verify(roleMapper, never()).deleteById(99L);
    }

    @Test
    void rejectsDeletionWhenRoleIsAssignedToUser() {
        when(roleMapper.selectByIdForUpdate(7L))
                .thenReturn(role(7L, "AUDITOR", "审计员"));
        when(userRoleMapper.selectCount(any())).thenReturn(1L);
        when(rolePermissionMapper.selectCount(any())).thenReturn(0L);

        assertApiException(() -> service.delete(7L),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verify(roleMapper, never()).deleteById(7L);
    }

    @Test
    void rejectsDeletionWhenRoleHasPermissionGrant() {
        when(roleMapper.selectByIdForUpdate(7L))
                .thenReturn(role(7L, "AUDITOR", "审计员"));
        when(userRoleMapper.selectCount(any())).thenReturn(0L);
        when(rolePermissionMapper.selectCount(any())).thenReturn(1L);

        assertApiException(() -> service.delete(7L),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verify(roleMapper, never()).deleteById(7L);
    }

    @Test
    void deletesUnreferencedRoleAfterLockAndReferenceChecks() {
        when(roleMapper.selectByIdForUpdate(7L))
                .thenReturn(role(7L, "AUDITOR", "审计员"));
        when(userRoleMapper.selectCount(any())).thenReturn(0L);
        when(rolePermissionMapper.selectCount(any())).thenReturn(0L);

        service.delete(7L);

        InOrder order = inOrder(roleMapper, userRoleMapper, rolePermissionMapper);
        order.verify(roleMapper).selectByIdForUpdate(7L);
        order.verify(userRoleMapper).selectCount(any());
        order.verify(rolePermissionMapper).selectCount(any());
        order.verify(roleMapper).deleteById(7L);
    }

    @Test
    void convertsDeleteIntegrityFailureToRbacConflict() {
        when(roleMapper.selectByIdForUpdate(7L))
                .thenReturn(role(7L, "AUDITOR", "审计员"));
        when(userRoleMapper.selectCount(any())).thenReturn(0L);
        when(rolePermissionMapper.selectCount(any())).thenReturn(0L);
        when(roleMapper.deleteById(7L))
                .thenThrow(new DataIntegrityViolationException("foreign key"));

        assertApiException(() -> service.delete(7L),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");
    }

    @Test
    void writeMethodsDeclareTransactionBoundaries() throws NoSuchMethodException {
        assertThat(IamRoleServiceImpl.class
                .getMethod("create", CreateRoleCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamRoleServiceImpl.class
                .getMethod("update", long.class, UpdateRoleCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamRoleServiceImpl.class
                .getMethod("delete", long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    private static IamRole role(long id, String code, String name) {
        IamRole role = new IamRole();
        role.setId(id);
        role.setCode(code);
        role.setName(name);
        role.setCreatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return role;
    }

    private static IamRolePermission rolePermission(long roleId, long permissionId) {
        IamRolePermission permission = new IamRolePermission();
        permission.setRoleId(roleId);
        permission.setPermissionId(permissionId);
        return permission;
    }

    private static IamPermission permission(long permissionId) {
        IamPermission permission = new IamPermission();
        permission.setId(permissionId);
        permission.setCode("PERMISSION_" + permissionId);
        permission.setName("权限 " + permissionId);
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
