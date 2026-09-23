package com.flowdesk.iam.application.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.domain.IamPermission;
import com.flowdesk.iam.domain.IamRolePermission;
import com.flowdesk.iam.application.result.PermissionResult;
import com.flowdesk.iam.application.command.CreatePermissionCommand;
import com.flowdesk.iam.application.query.PermissionQuery;
import com.flowdesk.iam.application.command.UpdatePermissionCommand;
import com.flowdesk.iam.mapper.IamPermissionMapper;
import com.flowdesk.iam.mapper.IamRolePermissionMapper;
import com.flowdesk.support.MybatisPlusTestMetadata;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IamPermissionServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-21T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final IamPermissionMapper permissionMapper =
            mock(IamPermissionMapper.class);
    private final IamRolePermissionMapper rolePermissionMapper =
            mock(IamRolePermissionMapper.class);
    private final IamPermissionServiceImpl service =
            new IamPermissionServiceImpl(
                    CLOCK, permissionMapper, rolePermissionMapper);

    @BeforeAll
    static void initializeMybatisPlusMetadata() {
        MybatisPlusTestMetadata.initialize(
                IamPermission.class, IamRolePermission.class);
    }

    @Test
    void rejectsNonPositivePermissionIdWithoutQueryingDatabase() {
        assertApiException(() -> service.getById(0),
                HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND");

        verifyNoInteractions(permissionMapper, rolePermissionMapper);
    }

    @Test
    void returnsNotFoundWhenRequestedPermissionDoesNotExist() {
        when(permissionMapper.selectById(99L)).thenReturn(null);

        assertApiException(() -> service.getById(99L),
                HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND");

        verifyNoInteractions(rolePermissionMapper);
    }

    @Test
    void returnsPermissionDetailWithOrderedRoleIds() {
        IamPermission permission = permission(7L, "TICKET_VIEW", "查看工单");
        when(permissionMapper.selectById(7L)).thenReturn(permission);
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(
                rolePermission(10L, 7L),
                rolePermission(20L, 7L)));

        PermissionResult result = service.getById(7L);

        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.code()).isEqualTo("TICKET_VIEW");
        assertThat(result.createdAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThat(result.roleIds()).containsExactly(10L, 20L);
        verify(permissionMapper).selectById(7L);
        verify(rolePermissionMapper).selectList(any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void usesIdAscendingAsDefaultPageOrder() {
        PermissionQuery query = new PermissionQuery();
        IamPermission permission = permission(7L, "TICKET_VIEW", "查看工单");
        doAnswer(invocation -> {
            Page<IamPermission> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of(permission));
            requestedPage.setTotal(1);
            return requestedPage;
        }).when(permissionMapper).selectPage(any(Page.class), any());
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of());

        service.page(query);

        ArgumentCaptor<Page<IamPermission>> pageCaptor =
                ArgumentCaptor.forClass((Class) Page.class);
        verify(permissionMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().orders()).singleElement().satisfies(order -> {
            assertThat(order.getColumn()).isEqualTo("id");
            assertThat(order.isAsc()).isTrue();
        });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void appendsIdAscendingAsStableTieBreakerForCustomPageOrder() {
        PermissionQuery query = new PermissionQuery();
        query.setOrderBy("name");
        query.setOrderDirection("desc");
        doAnswer(invocation -> {
            Page<IamPermission> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of());
            requestedPage.setTotal(0);
            return requestedPage;
        }).when(permissionMapper).selectPage(any(Page.class), any());

        service.page(query);

        ArgumentCaptor<Page<IamPermission>> pageCaptor =
                ArgumentCaptor.forClass((Class) Page.class);
        verify(permissionMapper).selectPage(pageCaptor.capture(), any());
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
    void loadsPageRolesInOneBatchAndUsesEmptyListForPermissionsWithoutRoles() {
        PermissionQuery query = new PermissionQuery();
        query.setOrderBy("code");
        query.setOrderDirection("desc");
        IamPermission view = permission(7L, "TICKET_VIEW", "查看工单");
        IamPermission edit = permission(8L, "TICKET_EDIT", "编辑工单");
        doAnswer(invocation -> {
            Page<IamPermission> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of(view, edit));
            requestedPage.setTotal(2);
            return requestedPage;
        }).when(permissionMapper).selectPage(any(Page.class), any());
        when(rolePermissionMapper.selectList(any()))
                .thenReturn(List.of(rolePermission(10L, 7L)));

        PageResult<PermissionResult> result = service.page(query);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).roleIds()).containsExactly(10L);
        assertThat(result.items().get(1).roleIds()).isEmpty();
        verify(rolePermissionMapper).selectList(any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void returnsEmptyPageWithoutQueryingRoleRelations() {
        PermissionQuery query = new PermissionQuery();
        query.setOrderBy("code");
        doAnswer(invocation -> {
            Page<IamPermission> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of());
            requestedPage.setTotal(0);
            return requestedPage;
        }).when(permissionMapper).selectPage(any(Page.class), any());

        PageResult<PermissionResult> result = service.page(query);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isZero();
        verifyNoInteractions(rolePermissionMapper);
    }

    @Test
    void rejectsExistingPermissionCodeBeforeInsert() {
        CreatePermissionCommand request = new CreatePermissionCommand(
                "TICKET_VIEW", "查看工单", null);
        when(permissionMapper.selectCount(any())).thenReturn(1L);

        assertApiException(() -> service.create(request),
                HttpStatus.CONFLICT, "PERMISSION_CODE_CONFLICT");

        verify(permissionMapper, never()).insert(any(IamPermission.class));
    }

    @Test
    void createsPermissionWithUtcTimeAndReturnsGeneratedId() {
        CreatePermissionCommand request = new CreatePermissionCommand(
                "TICKET_VIEW", "查看工单", "允许查看工单");
        when(permissionMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            IamPermission inserted = invocation.getArgument(0);
            inserted.setId(7L);
            return 1;
        }).when(permissionMapper).insert(any(IamPermission.class));

        PermissionResult result = service.create(request);

        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.code()).isEqualTo("TICKET_VIEW");
        assertThat(result.name()).isEqualTo("查看工单");
        assertThat(result.description()).isEqualTo("允许查看工单");
        assertThat(result.createdAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThat(result.roleIds()).isEmpty();
    }

    @Test
    void convertsConcurrentDuplicateKeyFailureToPermissionCodeConflict() {
        CreatePermissionCommand request = new CreatePermissionCommand(
                "TICKET_VIEW", "查看工单", null);
        when(permissionMapper.selectCount(any())).thenReturn(0L);
        doThrow(new DuplicateKeyException("duplicate"))
                .when(permissionMapper).insert(any(IamPermission.class));

        assertApiException(() -> service.create(request),
                HttpStatus.CONFLICT, "PERMISSION_CODE_CONFLICT");
    }

    @Test
    void returnsNotFoundWhenLockedPermissionDoesNotExistDuringUpdate() {
        when(permissionMapper.selectByIdForUpdate(99L)).thenReturn(null);

        assertApiException(
                () -> service.update(
                        99L, new UpdatePermissionCommand("新名称", null)),
                HttpStatus.NOT_FOUND,
                "PERMISSION_NOT_FOUND");

        verify(permissionMapper, never()).update(any(), any());
    }

    @Test
    void updatesOnlyMutablePermissionDetailsAfterLockingPermission() {
        IamPermission permission = permission(7L, "TICKET_VIEW", "旧名称");
        permission.setDescription("旧说明");
        when(permissionMapper.selectByIdForUpdate(7L)).thenReturn(permission);
        when(rolePermissionMapper.selectList(any()))
                .thenReturn(List.of(rolePermission(10L, 7L)));

        PermissionResult result = service.update(
                7L, new UpdatePermissionCommand("新名称", null));

        assertThat(result.code()).isEqualTo("TICKET_VIEW");
        assertThat(result.name()).isEqualTo("新名称");
        assertThat(result.description()).isNull();
        assertThat(result.roleIds()).containsExactly(10L);
        InOrder order = inOrder(permissionMapper, rolePermissionMapper);
        order.verify(permissionMapper).selectByIdForUpdate(7L);
        order.verify(permissionMapper).update(isNull(), any());
        order.verify(rolePermissionMapper).selectList(any());
    }

    @Test
    void alwaysProtectsRbacManageFromDeletion() {
        when(permissionMapper.selectByIdForUpdate(1L))
                .thenReturn(permission(1L, "RBAC_MANAGE", "管理 RBAC"));

        assertApiException(() -> service.delete(1L),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verifyNoInteractions(rolePermissionMapper);
        verify(permissionMapper, never()).deleteById(1L);
    }

    @Test
    void returnsNotFoundWhenLockedPermissionDoesNotExistDuringDeletion() {
        when(permissionMapper.selectByIdForUpdate(99L)).thenReturn(null);

        assertApiException(() -> service.delete(99L),
                HttpStatus.NOT_FOUND, "PERMISSION_NOT_FOUND");

        verifyNoInteractions(rolePermissionMapper);
        verify(permissionMapper, never()).deleteById(99L);
    }

    @Test
    void rejectsDeletionWhenPermissionIsAssignedToRole() {
        when(permissionMapper.selectByIdForUpdate(7L))
                .thenReturn(permission(7L, "TICKET_VIEW", "查看工单"));
        when(rolePermissionMapper.selectCount(any())).thenReturn(1L);

        assertApiException(() -> service.delete(7L),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verify(permissionMapper, never()).deleteById(7L);
    }

    @Test
    void deletesUnreferencedPermissionAfterLockAndReferenceCheck() {
        when(permissionMapper.selectByIdForUpdate(7L))
                .thenReturn(permission(7L, "TICKET_VIEW", "查看工单"));
        when(rolePermissionMapper.selectCount(any())).thenReturn(0L);

        service.delete(7L);

        InOrder order = inOrder(permissionMapper, rolePermissionMapper);
        order.verify(permissionMapper).selectByIdForUpdate(7L);
        order.verify(rolePermissionMapper).selectCount(any());
        order.verify(permissionMapper).deleteById(7L);
    }

    @Test
    void convertsDeleteIntegrityFailureToRbacConflict() {
        when(permissionMapper.selectByIdForUpdate(7L))
                .thenReturn(permission(7L, "TICKET_VIEW", "查看工单"));
        when(rolePermissionMapper.selectCount(any())).thenReturn(0L);
        when(permissionMapper.deleteById(7L))
                .thenThrow(new DataIntegrityViolationException("foreign key"));

        assertApiException(() -> service.delete(7L),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");
    }

    @Test
    void writeMethodsDeclareTransactionBoundaries() throws NoSuchMethodException {
        assertThat(IamPermissionServiceImpl.class
                .getMethod("create", CreatePermissionCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamPermissionServiceImpl.class
                .getMethod("update", long.class, UpdatePermissionCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamPermissionServiceImpl.class
                .getMethod("delete", long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    private static IamPermission permission(long id, String code, String name) {
        IamPermission permission = new IamPermission();
        permission.setId(id);
        permission.setCode(code);
        permission.setName(name);
        permission.setCreatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return permission;
    }

    private static IamRolePermission rolePermission(
            long roleId, long permissionId) {
        IamRolePermission relation = new IamRolePermission();
        relation.setRoleId(roleId);
        relation.setPermissionId(permissionId);
        return relation;
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
