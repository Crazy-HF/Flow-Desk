package com.flowdesk.iam.application.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.domain.IamRole;
import com.flowdesk.iam.domain.IamUser;
import com.flowdesk.iam.domain.IamUserRole;
import com.flowdesk.iam.domain.IamUserStatus;
import com.flowdesk.iam.application.result.UserRoleResult;
import com.flowdesk.iam.application.command.GrantRoleToUsersCommand;
import com.flowdesk.iam.application.command.GrantUserRolesCommand;
import com.flowdesk.iam.application.query.UserRoleQuery;
import com.flowdesk.iam.application.command.RevokeUserRolesCommand;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.mapper.IamUserMapper;
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
import static org.mockito.ArgumentMatchers.anyLong;
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
 * 用户角色授权服务的单元测试（`TASK-058` 第二片）。
 *
 * <p>覆盖分页白名单、批量增量授予、批量撤销与清空全部、幂等、审计两列、用户侧唯一保留的保护规则（最后一个启用管理员；2026-09-22 起不再保护"最后一个角色"，撤销允许零角色）、
 * 会话撤销范围与顺序，以及锁等待超时映射为 {@code 409/RBAC_CONFLICT}。真实 MySQL 行为由
 * {@code IamUserRoleServiceIT} 负责。</p>
 */
class IamUserRoleServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-22T02:30:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long USER_ID = 11L;
    private static final long OPERATOR_ID = 3L;
    private static final long ROLE_EMPLOYEE = 1L;
    private static final long ROLE_SUPPORT = 2L;
    private static final long ROLE_ADMIN = 3L;

    private final IamUserRoleMapper userRoleMapper = mock(IamUserRoleMapper.class);
    private final IamUserMapper userMapper = mock(IamUserMapper.class);
    private final IamRoleMapper roleMapper = mock(IamRoleMapper.class);
    private final CurrentOperatorPort currentOperatorPort = mock(CurrentOperatorPort.class);
    private final SessionRevocationPort sessionRevocationPort = mock(SessionRevocationPort.class);

    private final IamUserRoleServiceImpl service = new IamUserRoleServiceImpl(
            CLOCK, currentOperatorPort, sessionRevocationPort,
            userRoleMapper, userMapper, roleMapper);

    @BeforeAll
    static void initializeMybatisPlusMetadata() {
        MybatisPlusTestMetadata.initialize(
                IamUserRole.class, IamUser.class, IamRole.class);
    }

    // ---------- 分页 ----------

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void defaultsToGrantedAtDescendingWithPrimaryKeyTieBreakers() {
        UserRoleQuery query = new UserRoleQuery();
        query.setUserId(USER_ID);
        doAnswer(invocation -> {
            Page<IamUserRole> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of());
            requestedPage.setTotal(0);
            return requestedPage;
        }).when(userRoleMapper).selectPage(any(Page.class), any());

        service.page(query);

        ArgumentCaptor<Page<IamUserRole>> pageCaptor =
                ArgumentCaptor.forClass((Class) Page.class);
        verify(userRoleMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().orders()).satisfiesExactly(
                order -> {
                    assertThat(order.getColumn()).isEqualTo("granted_at");
                    assertThat(order.isAsc()).isFalse();
                },
                order -> {
                    assertThat(order.getColumn()).isEqualTo("user_id");
                    assertThat(order.isAsc()).isTrue();
                },
                order -> {
                    assertThat(order.getColumn()).isEqualTo("role_id");
                    assertThat(order.isAsc()).isTrue();
                });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void keepsRequestedWhitelistedOrderThenPrimaryKeyTieBreakers() {
        UserRoleQuery query = new UserRoleQuery();
        query.setRoleId(ROLE_SUPPORT);
        query.setOrderBy("granted_at");
        query.setOrderDirection("asc");
        doAnswer(invocation -> {
            Page<IamUserRole> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of());
            requestedPage.setTotal(0);
            return requestedPage;
        }).when(userRoleMapper).selectPage(any(Page.class), any());

        service.page(query);

        ArgumentCaptor<Page<IamUserRole>> pageCaptor =
                ArgumentCaptor.forClass((Class) Page.class);
        verify(userRoleMapper).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getValue().orders()).satisfiesExactly(
                order -> {
                    assertThat(order.getColumn()).isEqualTo("granted_at");
                    assertThat(order.isAsc()).isTrue();
                },
                order -> assertThat(order.getColumn()).isEqualTo("user_id"),
                order -> assertThat(order.getColumn()).isEqualTo("role_id"));
    }

    @Test
    void rejectsOrderFieldOutsideWhitelistBeforeQueryingDatabase() {
        UserRoleQuery query = new UserRoleQuery();
        query.setUserId(USER_ID);
        query.setOrderBy("user_id");

        assertApiException(() -> service.page(query),
                HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(userRoleMapper, userMapper, roleMapper);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void returnsEmptyPageWithoutLoadingUsersAndRoles() {
        UserRoleQuery query = new UserRoleQuery();
        query.setUserId(USER_ID);
        doAnswer(invocation -> {
            Page<IamUserRole> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of());
            requestedPage.setTotal(0);
            return requestedPage;
        }).when(userRoleMapper).selectPage(any(Page.class), any());

        PageResult<UserRoleResult> result = service.page(query);

        assertThat(result.items()).isEmpty();
        verifyNoInteractions(userMapper, roleMapper);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void loadsUsersAndRolesInOneBatchEachAndAssemblesGrantView() {
        UserRoleQuery query = new UserRoleQuery();
        query.setRoleId(ROLE_EMPLOYEE);
        doAnswer(invocation -> {
            Page<IamUserRole> requestedPage = invocation.getArgument(0);
            requestedPage.setRecords(List.of(
                    grant(USER_ID, ROLE_EMPLOYEE, OPERATOR_ID),
                    grant(USER_ID + 1, ROLE_EMPLOYEE, null)));
            requestedPage.setTotal(2);
            return requestedPage;
        }).when(userRoleMapper).selectPage(any(Page.class), any());
        when(userMapper.selectBatchIds(any())).thenReturn(List.of(
                user(USER_ID, "employee", IamUserStatus.ENABLED),
                user(USER_ID + 1, "it", IamUserStatus.ENABLED)));
        when(roleMapper.selectBatchIds(any())).thenReturn(List.of(
                role(ROLE_EMPLOYEE, "EMPLOYEE", "员工")));

        PageResult<UserRoleResult> result = service.page(query);

        assertThat(result.totalElements()).isEqualTo(2);
        assertThat(result.items()).hasSize(2);
        UserRoleResult first = result.items().getFirst();
        assertThat(first.userId()).isEqualTo(USER_ID);
        assertThat(first.username()).isEqualTo("employee");
        assertThat(first.roleId()).isEqualTo(ROLE_EMPLOYEE);
        assertThat(first.roleCode()).isEqualTo("EMPLOYEE");
        assertThat(first.roleName()).isEqualTo("员工");
        assertThat(first.grantedBy()).isEqualTo(OPERATOR_ID);
        assertThat(first.grantedAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThat(result.items().get(1).username()).isEqualTo("it");
        verify(userMapper).selectBatchIds(any());
        verify(roleMapper).selectBatchIds(any());
    }

    // ---------- 批量增量授予 ----------

    @Test
    void grantsOnlyMissingRolesWritesAuditColumnsAndRevokesTargetUserOnce() {
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_EMPLOYEE, ROLE_SUPPORT)))
                .thenReturn(List.of(
                        role(ROLE_EMPLOYEE, "EMPLOYEE", "员工"),
                        role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持")));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "employee", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(grant(USER_ID, ROLE_EMPLOYEE, null)));
        when(currentOperatorPort.currentUserId()).thenReturn(OPERATOR_ID);

        List<UserRoleResult> result = service.grant(
                new GrantUserRolesCommand(USER_ID, List.of(ROLE_SUPPORT, ROLE_EMPLOYEE)));

        assertThat(result).extracting(UserRoleResult::roleId)
                .containsExactly(ROLE_EMPLOYEE, ROLE_SUPPORT);
        assertThat(result.getFirst().grantedBy()).isNull();
        assertThat(result.get(1).roleCode()).isEqualTo("IT_SUPPORT");

        ArgumentCaptor<IamUserRole> inserted =
                ArgumentCaptor.forClass(IamUserRole.class);
        verify(userRoleMapper, times(1)).insert(inserted.capture());
        assertThat(inserted.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(inserted.getValue().getRoleId()).isEqualTo(ROLE_SUPPORT);
        assertThat(inserted.getValue().getGrantedBy()).isEqualTo(OPERATOR_ID);
        assertThat(inserted.getValue().getGrantedAt())
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

        verify(sessionRevocationPort, times(1)).revokeAll(USER_ID);

        InOrder order = inOrder(roleMapper, userMapper, userRoleMapper,
                sessionRevocationPort);
        order.verify(roleMapper).selectByIdsForUpdate(List.of(ROLE_EMPLOYEE, ROLE_SUPPORT));
        order.verify(userMapper).selectByIdForUpdate(USER_ID);
        order.verify(userRoleMapper).selectByUserIdForUpdate(USER_ID);
        order.verify(sessionRevocationPort).revokeAll(USER_ID);
        order.verify(userRoleMapper).insert(any(IamUserRole.class));
    }

    @Test
    void repeatedGrantOfExistingRolesIsIdempotentAndDoesNotRevokeSession() {
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_EMPLOYEE)))
                .thenReturn(List.of(role(ROLE_EMPLOYEE, "EMPLOYEE", "员工")));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "employee", IamUserStatus.ENABLED));
        IamUserRole existing = grant(USER_ID, ROLE_EMPLOYEE, OPERATOR_ID);
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(existing));

        List<UserRoleResult> result = service.grant(
                new GrantUserRolesCommand(USER_ID, List.of(ROLE_EMPLOYEE)));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().grantedBy()).isEqualTo(OPERATOR_ID);
        assertThat(result.getFirst().grantedAt())
                .isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        verify(userRoleMapper, never()).insert(any(IamUserRole.class));
        verifyNoInteractions(currentOperatorPort);
        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void deduplicatesAndSortsRoleIdsBeforeLockingRoles() {
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_EMPLOYEE, ROLE_SUPPORT)))
                .thenReturn(List.of(
                        role(ROLE_EMPLOYEE, "EMPLOYEE", "员工"),
                        role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持")));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "employee", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(
                        grant(USER_ID, ROLE_EMPLOYEE, null),
                        grant(USER_ID, ROLE_SUPPORT, null)));

        service.grant(new GrantUserRolesCommand(
                USER_ID, List.of(ROLE_SUPPORT, ROLE_EMPLOYEE, ROLE_SUPPORT)));

        verify(roleMapper).selectByIdsForUpdate(List.of(ROLE_EMPLOYEE, ROLE_SUPPORT));
    }

    @Test
    void rejectsMissingRoleBeforeLockingUserAndBeforeAnyWrite() {
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_SUPPORT)))
                .thenReturn(List.of());

        assertApiException(
                () -> service.grant(new GrantUserRolesCommand(USER_ID, List.of(ROLE_SUPPORT))),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");

        verify(userMapper, never()).selectByIdForUpdate(anyLong());
        verify(userRoleMapper, never()).insert(any(IamUserRole.class));
        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void rejectsMissingUserBeforeAnyWrite() {
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_SUPPORT)))
                .thenReturn(List.of(role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持")));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(null);

        assertApiException(
                () -> service.grant(new GrantUserRolesCommand(USER_ID, List.of(ROLE_SUPPORT))),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");

        verify(userRoleMapper, never()).insert(any(IamUserRole.class));
        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void convertsLockWaitFailureToRbacConflict() {
        doThrow(new PessimisticLockingFailureException("lock wait timeout"))
                .when(roleMapper).selectByIdsForUpdate(any());

        assertApiException(
                () -> service.grant(new GrantUserRolesCommand(USER_ID, List.of(ROLE_SUPPORT))),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verifyNoInteractions(sessionRevocationPort);
    }

    // ---------- 单条撤销 ----------

    @Test
    void revokesGrantAfterLockingRoleThenUserAndRevokesSessionBeforeDelete() {
        when(roleMapper.selectByIdForUpdate(ROLE_SUPPORT))
                .thenReturn(role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持"));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "employee", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(
                        grant(USER_ID, ROLE_EMPLOYEE, null),
                        grant(USER_ID, ROLE_SUPPORT, null)));

        service.revoke(USER_ID, ROLE_SUPPORT);

        InOrder order = inOrder(roleMapper, userMapper, userRoleMapper,
                sessionRevocationPort);
        order.verify(roleMapper).selectByIdForUpdate(ROLE_SUPPORT);
        order.verify(userMapper).selectByIdForUpdate(USER_ID);
        order.verify(userRoleMapper).selectByUserIdForUpdate(USER_ID);
        order.verify(sessionRevocationPort).revokeAll(USER_ID);
        order.verify(userRoleMapper).delete(any());
    }

    @Test
    void rejectsRevokeWhenGrantDoesNotExist() {
        when(roleMapper.selectByIdForUpdate(ROLE_SUPPORT))
                .thenReturn(role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持"));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "employee", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(grant(USER_ID, ROLE_EMPLOYEE, null)));

        assertApiException(() -> service.revoke(USER_ID, ROLE_SUPPORT),
                HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND");

        verifyNoInteractions(sessionRevocationPort);
        verify(userRoleMapper, never()).delete(any());
    }

    @Test
    void rejectsRevokeWhenRoleIsMissing() {
        when(roleMapper.selectByIdForUpdate(ROLE_SUPPORT)).thenReturn(null);

        assertApiException(() -> service.revoke(USER_ID, ROLE_SUPPORT),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");

        verify(userMapper, never()).selectByIdForUpdate(anyLong());
        verify(userRoleMapper, never()).delete(any());
    }

    @Test
    void rejectsRevokeWhenUserIsMissing() {
        when(roleMapper.selectByIdForUpdate(ROLE_SUPPORT))
                .thenReturn(role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持"));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(null);

        assertApiException(() -> service.revoke(USER_ID, ROLE_SUPPORT),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");

        verify(userRoleMapper, never()).delete(any());
    }

    /**
     * 决策变更（2026-09-22 用户确认）：废弃"用户必须至少保留一个角色"的保护规则，
     * 允许撤销后用户不再拥有任何角色。
     */
    @Test
    void allowsRevokingUsersLastRemainingRole() {
        when(roleMapper.selectByIdForUpdate(ROLE_SUPPORT))
                .thenReturn(role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持"));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "employee", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(grant(USER_ID, ROLE_SUPPORT, null)));

        service.revoke(USER_ID, ROLE_SUPPORT);

        verify(sessionRevocationPort).revokeAll(USER_ID);
        verify(userRoleMapper).delete(any());
    }

    @Test
    void protectsLastEnabledAdministratorFromLosingSystemAdminRole() {
        when(roleMapper.selectByIdForUpdate(ROLE_ADMIN))
                .thenReturn(role(ROLE_ADMIN, "SYSTEM_ADMIN", "系统管理员"));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "admin", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(
                        grant(USER_ID, ROLE_ADMIN, null),
                        grant(USER_ID, ROLE_SUPPORT, null)));
        when(userRoleMapper.selectList(any()))
                .thenReturn(List.of(grant(USER_ID, ROLE_ADMIN, null)));
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertApiException(() -> service.revoke(USER_ID, ROLE_ADMIN),
                HttpStatus.CONFLICT, "LAST_ADMIN_PROTECTED");

        verifyNoInteractions(sessionRevocationPort);
        verify(userRoleMapper, never()).delete(any());
    }

    @Test
    void allowsRevokingSystemAdminRoleWhenAnotherEnabledAdministratorRemains() {
        when(roleMapper.selectByIdForUpdate(ROLE_ADMIN))
                .thenReturn(role(ROLE_ADMIN, "SYSTEM_ADMIN", "系统管理员"));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "admin", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(
                        grant(USER_ID, ROLE_ADMIN, null),
                        grant(USER_ID, ROLE_SUPPORT, null)));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                grant(USER_ID, ROLE_ADMIN, null),
                grant(USER_ID + 1, ROLE_ADMIN, null)));
        when(userMapper.selectCount(any())).thenReturn(2L);

        service.revoke(USER_ID, ROLE_ADMIN);

        verify(sessionRevocationPort).revokeAll(USER_ID);
        verify(userRoleMapper).delete(any());
    }

    @Test
    void skipsLastAdministratorCheckForDisabledUser() {
        when(roleMapper.selectByIdForUpdate(ROLE_ADMIN))
                .thenReturn(role(ROLE_ADMIN, "SYSTEM_ADMIN", "系统管理员"));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "admin", IamUserStatus.DISABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(
                        grant(USER_ID, ROLE_ADMIN, null),
                        grant(USER_ID, ROLE_SUPPORT, null)));

        service.revoke(USER_ID, ROLE_ADMIN);

        verify(userRoleMapper, never()).selectList(any());
        verify(userMapper, never()).selectCount(any());
        verify(userRoleMapper).delete(any());
    }

    @Test
    void revokeLockWaitFailureIsConvertedToRbacConflict() {
        doThrow(new PessimisticLockingFailureException("deadlock"))
                .when(roleMapper).selectByIdForUpdate(ROLE_SUPPORT);

        assertApiException(() -> service.revoke(USER_ID, ROLE_SUPPORT),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verify(userRoleMapper, never()).delete(any());
    }

    @Test
    void writeMethodsDeclareTransactionBoundaries() throws NoSuchMethodException {
        assertThat(IamUserRoleServiceImpl.class
                .getMethod("grant", GrantUserRolesCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamUserRoleServiceImpl.class
                .getMethod("revoke", long.class, long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamUserRoleServiceImpl.class
                .getMethod("revokeBatch", RevokeUserRolesCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamUserRoleServiceImpl.class
                .getMethod("clearAll", long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamUserRoleServiceImpl.class
                .getMethod("grantUsers", GrantRoleToUsersCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    // ---------- 批量撤销（POST /user-roles/actions/revoke） ----------

    @Test
    void revokesRequestedRolesInOneBatchAfterLockingRolesThenUser() {
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_EMPLOYEE, ROLE_SUPPORT)))
                .thenReturn(List.of(
                        role(ROLE_EMPLOYEE, "EMPLOYEE", "员工"),
                        role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持")));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "employee", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID)).thenReturn(List.of(
                grant(USER_ID, ROLE_EMPLOYEE, null),
                grant(USER_ID, ROLE_SUPPORT, null)));
        when(userRoleMapper.delete(any())).thenReturn(2);

        service.revokeBatch(new RevokeUserRolesCommand(
                USER_ID, List.of(ROLE_SUPPORT, ROLE_EMPLOYEE, ROLE_SUPPORT)));

        verify(roleMapper).selectByIdsForUpdate(List.of(ROLE_EMPLOYEE, ROLE_SUPPORT));
        verify(sessionRevocationPort, times(1)).revokeAll(USER_ID);

        InOrder order = inOrder(roleMapper, userMapper, userRoleMapper,
                sessionRevocationPort);
        order.verify(roleMapper).selectByIdsForUpdate(List.of(ROLE_EMPLOYEE, ROLE_SUPPORT));
        order.verify(userMapper).selectByIdForUpdate(USER_ID);
        order.verify(userRoleMapper).selectByUserIdForUpdate(USER_ID);
        order.verify(sessionRevocationPort).revokeAll(USER_ID);
        order.verify(userRoleMapper).delete(any());
    }

    @Test
    void allowsBatchRevokeThatLeavesUserWithoutAnyRole() {
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_SUPPORT)))
                .thenReturn(List.of(role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持")));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "employee", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(grant(USER_ID, ROLE_SUPPORT, null)));
        when(userRoleMapper.delete(any())).thenReturn(1);

        service.revokeBatch(new RevokeUserRolesCommand(USER_ID, List.of(ROLE_SUPPORT)));

        verify(sessionRevocationPort).revokeAll(USER_ID);
        verify(userRoleMapper).delete(any());
    }

    @Test
    void rejectsBatchRevokeWhenAnyGrantIsMissingWithoutWriteOrRevocation() {
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_EMPLOYEE, ROLE_SUPPORT)))
                .thenReturn(List.of(
                        role(ROLE_EMPLOYEE, "EMPLOYEE", "员工"),
                        role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持")));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "employee", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(grant(USER_ID, ROLE_EMPLOYEE, null)));

        assertApiException(() -> service.revokeBatch(new RevokeUserRolesCommand(
                        USER_ID, List.of(ROLE_EMPLOYEE, ROLE_SUPPORT))),
                HttpStatus.NOT_FOUND, "GRANT_NOT_FOUND");

        verifyNoInteractions(sessionRevocationPort);
        verify(userRoleMapper, never()).delete(any());
    }

    @Test
    void rejectsBatchRevokeWhenAnyRoleIsMissing() {
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_EMPLOYEE, ROLE_SUPPORT)))
                .thenReturn(List.of(role(ROLE_EMPLOYEE, "EMPLOYEE", "员工")));

        assertApiException(() -> service.revokeBatch(new RevokeUserRolesCommand(
                        USER_ID, List.of(ROLE_EMPLOYEE, ROLE_SUPPORT))),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");

        verify(userMapper, never()).selectByIdForUpdate(anyLong());
    }

    @Test
    void rejectsBatchRevokeWhenAnyUserIsMissing() {
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_SUPPORT)))
                .thenReturn(List.of(role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持")));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(null);

        assertApiException(() -> service.revokeBatch(new RevokeUserRolesCommand(
                        USER_ID, List.of(ROLE_SUPPORT))),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    @Test
    void protectsLastEnabledAdministratorDuringBatchRevoke() {
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_ADMIN)))
                .thenReturn(List.of(role(ROLE_ADMIN, "SYSTEM_ADMIN", "系统管理员")));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "admin", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID)).thenReturn(List.of(
                grant(USER_ID, ROLE_ADMIN, null),
                grant(USER_ID, ROLE_SUPPORT, null)));
        when(userRoleMapper.selectList(any()))
                .thenReturn(List.of(grant(USER_ID, ROLE_ADMIN, null)));
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertApiException(() -> service.revokeBatch(new RevokeUserRolesCommand(
                        USER_ID, List.of(ROLE_ADMIN))),
                HttpStatus.CONFLICT, "LAST_ADMIN_PROTECTED");

        verifyNoInteractions(sessionRevocationPort);
        verify(userRoleMapper, never()).delete(any());
    }

    @Test
    void reportsBatchRevokeConflictWhenDeletedRowCountDiffers() {
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_EMPLOYEE, ROLE_SUPPORT)))
                .thenReturn(List.of(
                        role(ROLE_EMPLOYEE, "EMPLOYEE", "员工"),
                        role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持")));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "employee", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID)).thenReturn(List.of(
                grant(USER_ID, ROLE_EMPLOYEE, null),
                grant(USER_ID, ROLE_SUPPORT, null)));
        when(userRoleMapper.delete(any())).thenReturn(1);

        assertApiException(() -> service.revokeBatch(new RevokeUserRolesCommand(
                        USER_ID, List.of(ROLE_EMPLOYEE, ROLE_SUPPORT))),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");
    }

    @Test
    void convertsBatchRevokeLockFailureToRbacConflict() {
        doThrow(new PessimisticLockingFailureException("deadlock"))
                .when(roleMapper).selectByIdsForUpdate(any());

        assertApiException(() -> service.revokeBatch(new RevokeUserRolesCommand(
                        USER_ID, List.of(ROLE_SUPPORT))),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");
    }

    // ---------- 清空全部角色（DELETE /user-roles/users/{userId}） ----------

    @Test
    void clearAllRemovesEveryGrantAndRevokesSessionOnce() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                grant(USER_ID, ROLE_EMPLOYEE, null),
                grant(USER_ID, ROLE_SUPPORT, null)));
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_EMPLOYEE, ROLE_SUPPORT)))
                .thenReturn(List.of(
                        role(ROLE_EMPLOYEE, "EMPLOYEE", "员工"),
                        role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持")));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "employee", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID)).thenReturn(List.of(
                grant(USER_ID, ROLE_EMPLOYEE, null),
                grant(USER_ID, ROLE_SUPPORT, null)));
        when(userRoleMapper.delete(any())).thenReturn(2);

        service.clearAll(USER_ID);

        verify(sessionRevocationPort, times(1)).revokeAll(USER_ID);

        InOrder order = inOrder(roleMapper, userMapper, userRoleMapper,
                sessionRevocationPort);
        order.verify(roleMapper).selectByIdsForUpdate(List.of(ROLE_EMPLOYEE, ROLE_SUPPORT));
        order.verify(userMapper).selectByIdForUpdate(USER_ID);
        order.verify(sessionRevocationPort).revokeAll(USER_ID);
        order.verify(userRoleMapper).delete(any());
    }

    @Test
    void clearAllIsIdempotentWhenUserHasNoRoles() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "employee", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID)).thenReturn(List.of());

        service.clearAll(USER_ID);

        verifyNoInteractions(sessionRevocationPort);
        verify(userRoleMapper, never()).delete(any());
        verify(roleMapper, never()).selectByIdsForUpdate(any());
    }

    @Test
    void clearAllProtectsLastEnabledAdministrator() {
        // 第一次 selectList 取该用户现有角色，第二次取持有 SYSTEM_ADMIN 的用户
        when(userRoleMapper.selectList(any()))
                .thenReturn(List.of(
                        grant(USER_ID, ROLE_ADMIN, null),
                        grant(USER_ID, ROLE_SUPPORT, null)))
                .thenReturn(List.of(grant(USER_ID, ROLE_ADMIN, null)));
        // 服务端按角色 ID 升序加锁，因此这里必须按排序后的顺序桩定
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_SUPPORT, ROLE_ADMIN)))
                .thenReturn(List.of(
                        role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持"),
                        role(ROLE_ADMIN, "SYSTEM_ADMIN", "系统管理员")));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "admin", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID)).thenReturn(List.of(
                grant(USER_ID, ROLE_ADMIN, null),
                grant(USER_ID, ROLE_SUPPORT, null)));
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertApiException(() -> service.clearAll(USER_ID),
                HttpStatus.CONFLICT, "LAST_ADMIN_PROTECTED");

        verifyNoInteractions(sessionRevocationPort);
        verify(userRoleMapper, never()).delete(any());
    }

    @Test
    void clearAllRejectsMissingUser() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of());
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(null);

        assertApiException(() -> service.clearAll(USER_ID),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
    }

    @Test
    void clearAllReportsConflictWhenGrantsChangedConcurrently() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                grant(USER_ID, ROLE_EMPLOYEE, null),
                grant(USER_ID, ROLE_SUPPORT, null)));
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_EMPLOYEE, ROLE_SUPPORT)))
                .thenReturn(List.of(
                        role(ROLE_EMPLOYEE, "EMPLOYEE", "员工"),
                        role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持")));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "employee", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(grant(USER_ID, ROLE_EMPLOYEE, null)));

        assertApiException(() -> service.clearAll(USER_ID),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void clearAllReportsConflictWhenDeletedRowCountDiffers() {
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                grant(USER_ID, ROLE_EMPLOYEE, null),
                grant(USER_ID, ROLE_SUPPORT, null)));
        when(roleMapper.selectByIdsForUpdate(List.of(ROLE_EMPLOYEE, ROLE_SUPPORT)))
                .thenReturn(List.of(
                        role(ROLE_EMPLOYEE, "EMPLOYEE", "员工"),
                        role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持")));
        when(userMapper.selectByIdForUpdate(USER_ID))
                .thenReturn(user(USER_ID, "employee", IamUserStatus.ENABLED));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID)).thenReturn(List.of(
                grant(USER_ID, ROLE_EMPLOYEE, null),
                grant(USER_ID, ROLE_SUPPORT, null)));
        when(userRoleMapper.delete(any())).thenReturn(1);

        assertApiException(() -> service.clearAll(USER_ID),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");
    }

    // ---------- 一个角色授予多个用户（POST /user-roles/actions/grant-users） ----------

    @Test
    void grantsRoleToMissingUsersOnlyAndRevokesEachTargetOnce() {
        long secondUserId = USER_ID + 1;
        when(roleMapper.selectByIdForUpdate(ROLE_SUPPORT))
                .thenReturn(role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持"));
        when(userMapper.selectByIdsForUpdate(List.of(USER_ID, secondUserId)))
                .thenReturn(List.of(
                        user(USER_ID, "employee", IamUserStatus.ENABLED),
                        user(secondUserId, "it", IamUserStatus.ENABLED)));
        when(userRoleMapper.selectByRoleIdAndUserIdsForUpdate(
                ROLE_SUPPORT, List.of(USER_ID, secondUserId)))
                .thenReturn(List.of(grant(USER_ID, ROLE_SUPPORT, null)));
        when(currentOperatorPort.currentUserId()).thenReturn(OPERATOR_ID);

        List<UserRoleResult> result = service.grantUsers(
                new GrantRoleToUsersCommand(ROLE_SUPPORT, List.of(secondUserId, USER_ID, secondUserId)));

        assertThat(result).extracting(UserRoleResult::userId)
                .containsExactly(USER_ID, secondUserId);
        assertThat(result.getFirst().grantedBy()).isNull();
        assertThat(result.get(1).grantedBy()).isEqualTo(OPERATOR_ID);

        ArgumentCaptor<IamUserRole> inserted = ArgumentCaptor.forClass(IamUserRole.class);
        verify(userRoleMapper, times(1)).insert(inserted.capture());
        assertThat(inserted.getValue().getUserId()).isEqualTo(secondUserId);
        assertThat(inserted.getValue().getRoleId()).isEqualTo(ROLE_SUPPORT);
        assertThat(inserted.getValue().getGrantedBy()).isEqualTo(OPERATOR_ID);
        assertThat(inserted.getValue().getGrantedAt())
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));

        verify(sessionRevocationPort, times(1)).revokeAll(secondUserId);
        verify(sessionRevocationPort, never()).revokeAll(USER_ID);

        InOrder order = inOrder(roleMapper, userMapper, userRoleMapper,
                sessionRevocationPort);
        order.verify(roleMapper).selectByIdForUpdate(ROLE_SUPPORT);
        order.verify(userMapper).selectByIdsForUpdate(List.of(USER_ID, secondUserId));
        order.verify(userRoleMapper)
                .selectByRoleIdAndUserIdsForUpdate(ROLE_SUPPORT, List.of(USER_ID, secondUserId));
        order.verify(sessionRevocationPort).revokeAll(secondUserId);
        order.verify(userRoleMapper).insert(any(IamUserRole.class));
    }

    @Test
    void repeatedGrantUsersIsIdempotentAndDoesNotRevokeSessions() {
        when(roleMapper.selectByIdForUpdate(ROLE_SUPPORT))
                .thenReturn(role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持"));
        when(userMapper.selectByIdsForUpdate(List.of(USER_ID)))
                .thenReturn(List.of(user(USER_ID, "employee", IamUserStatus.ENABLED)));
        when(userRoleMapper.selectByRoleIdAndUserIdsForUpdate(ROLE_SUPPORT, List.of(USER_ID)))
                .thenReturn(List.of(grant(USER_ID, ROLE_SUPPORT, OPERATOR_ID)));

        List<UserRoleResult> result = service.grantUsers(
                new GrantRoleToUsersCommand(ROLE_SUPPORT, List.of(USER_ID)));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().grantedBy()).isEqualTo(OPERATOR_ID);
        verify(userRoleMapper, never()).insert(any(IamUserRole.class));
        verifyNoInteractions(currentOperatorPort);
        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void rejectsGrantUsersWhenAnyUserIsMissing() {
        long secondUserId = USER_ID + 1;
        when(roleMapper.selectByIdForUpdate(ROLE_SUPPORT))
                .thenReturn(role(ROLE_SUPPORT, "IT_SUPPORT", "IT 支持"));
        when(userMapper.selectByIdsForUpdate(List.of(USER_ID, secondUserId)))
                .thenReturn(List.of(user(USER_ID, "employee", IamUserStatus.ENABLED)));

        assertApiException(() -> service.grantUsers(new GrantRoleToUsersCommand(
                        ROLE_SUPPORT, List.of(USER_ID, secondUserId))),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");

        verify(userRoleMapper, never()).insert(any(IamUserRole.class));
        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void rejectsGrantUsersWhenRoleIsMissing() {
        when(roleMapper.selectByIdForUpdate(ROLE_SUPPORT)).thenReturn(null);

        assertApiException(() -> service.grantUsers(
                        new GrantRoleToUsersCommand(ROLE_SUPPORT, List.of(USER_ID))),
                HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND");

        verifyNoInteractions(userMapper, sessionRevocationPort);
    }

    @Test
    void convertsGrantUsersLockFailureToRbacConflict() {
        doThrow(new PessimisticLockingFailureException("lock wait timeout"))
                .when(roleMapper).selectByIdForUpdate(ROLE_SUPPORT);

        assertApiException(() -> service.grantUsers(
                        new GrantRoleToUsersCommand(ROLE_SUPPORT, List.of(USER_ID))),
                HttpStatus.CONFLICT, "RBAC_CONFLICT");

        verifyNoInteractions(sessionRevocationPort);
    }

    // ---------- 辅助 ----------

    private static IamUserRole grant(long userId, long roleId, Long grantedBy) {
        IamUserRole grant = new IamUserRole();
        grant.setUserId(userId);
        grant.setRoleId(roleId);
        grant.setGrantedBy(grantedBy);
        grant.setGrantedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return grant;
    }

    private static IamUser user(long id, String username, IamUserStatus status) {
        IamUser user = new IamUser();
        user.setId(id);
        user.setUsername(username);
        user.setStatus(status);
        return user;
    }

    private static IamRole role(long id, String code, String name) {
        IamRole role = new IamRole();
        role.setId(id);
        role.setCode(code);
        role.setName(name);
        role.setCreatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return role;
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
