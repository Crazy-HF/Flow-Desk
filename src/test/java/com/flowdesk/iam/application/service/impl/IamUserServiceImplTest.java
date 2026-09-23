package com.flowdesk.iam.application.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.application.command.CreateUserCommand;
import com.flowdesk.iam.application.command.ReplaceUserRolesCommand;
import com.flowdesk.iam.application.command.ResetUserPasswordCommand;
import com.flowdesk.iam.application.command.UpdateUserCommand;
import com.flowdesk.iam.application.command.UserStatusChangeCommand;
import com.flowdesk.iam.application.port.CurrentOperatorPort;
import com.flowdesk.iam.application.port.SessionRevocationPort;
import com.flowdesk.iam.application.query.UserQuery;
import com.flowdesk.iam.application.result.UserResult;
import com.flowdesk.iam.domain.IamRole;
import com.flowdesk.iam.domain.IamUser;
import com.flowdesk.iam.domain.IamUserRole;
import com.flowdesk.iam.domain.IamUserStatus;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.mapper.IamUserMapper;
import com.flowdesk.iam.mapper.IamUserRoleMapper;
import com.flowdesk.support.MybatisPlusTestMetadata;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

/**
 * 用户管理服务的业务规则：分页组装、创建与用户名唯一、版本条件更新、启停的幂等与
 * "最后启用管理员"保护、角色集合替换的差集语义与撤销会话时机。
 *
 * <p>契约见 {@code docs/api-design.md} 8.2 与 {@code docs/modules/rbac.md}；
 * 这里用替身固定"先编码密码再加锁""先撤 Redis 再提交 MySQL"等顺序约束。</p>
 */
class IamUserServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-09-23T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long USER_ID = 42L;
    private static final long OPERATOR_ID = 3L;
    private static final long SYSTEM_ADMIN_ROLE_ID = 1L;
    private static final long EMPLOYEE_ROLE_ID = 10L;
    private static final long SUPPORT_ROLE_ID = 20L;
    private static final String SYSTEM_ADMIN = "SYSTEM_ADMIN";
    private static final String ENCODED_PASSWORD = "$argon2id$encoded";

    private final IamUserMapper userMapper = mock(IamUserMapper.class);
    private final IamUserRoleMapper userRoleMapper = mock(IamUserRoleMapper.class);
    private final IamRoleMapper roleMapper = mock(IamRoleMapper.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final CurrentOperatorPort currentOperatorPort = mock(CurrentOperatorPort.class);
    private final SessionRevocationPort sessionRevocationPort = mock(SessionRevocationPort.class);

    private final IamUserServiceImpl service = new IamUserServiceImpl(
            userMapper, userRoleMapper, roleMapper, passwordEncoder,
            currentOperatorPort, sessionRevocationPort, CLOCK);

    @BeforeAll
    static void initializeMybatisPlusMetadata() {
        MybatisPlusTestMetadata.initialize(IamUser.class, IamUserRole.class, IamRole.class);
    }

    // ---------- updatePassword ----------

    @Test
    void updatePasswordReturnsFalseWhenUserDoesNotExist() {
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        assertThat(service.updatePassword(USER_ID, ENCODED_PASSWORD)).isFalse();

        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void updatePasswordUsesReadVersionAsCompareAndSetCondition() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 4L));
        when(userMapper.update(isNull(), any())).thenReturn(1);

        assertThat(service.updatePassword(USER_ID, ENCODED_PASSWORD)).isTrue();

        verify(userMapper).update(isNull(), any());
    }

    @Test
    void updatePasswordReportsConflictWhenConditionalUpdateAffectsNoRow() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 4L));
        when(userMapper.update(isNull(), any())).thenReturn(0);

        assertThat(service.updatePassword(USER_ID, ENCODED_PASSWORD)).isFalse();
    }

    // ---------- page ----------

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pageUsesIdAscendingWhenNoOrderRequested() {
        UserQuery query = new UserQuery();
        stubPage(query, List.of(), 0);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        service.page(query);

        ArgumentCaptor<Page<IamUser>> pageCaptor = ArgumentCaptor.forClass((Class) Page.class);
        verify(userMapper).selectUserPage(pageCaptor.capture(), any(), any(), any());
        assertThat(pageCaptor.getValue().orders()).singleElement().satisfies(order -> {
            assertThat(order.getColumn()).isEqualTo("id");
            assertThat(order.isAsc()).isTrue();
        });
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pageAppendsIdAscendingAsStableTieBreaker() {
        UserQuery query = new UserQuery();
        query.setOrderBy("display_name,status");
        query.setOrderDirection("desc");
        stubPage(query, List.of(), 0);

        service.page(query);

        ArgumentCaptor<Page<IamUser>> pageCaptor = ArgumentCaptor.forClass((Class) Page.class);
        verify(userMapper).selectUserPage(pageCaptor.capture(), any(), any(), any());
        assertThat(pageCaptor.getValue().orders()).satisfiesExactly(
                order -> {
                    assertThat(order.getColumn()).isEqualTo("display_name");
                    assertThat(order.isAsc()).isFalse();
                },
                order -> {
                    assertThat(order.getColumn()).isEqualTo("status");
                    assertThat(order.isAsc()).isFalse();
                },
                order -> {
                    assertThat(order.getColumn()).isEqualTo("id");
                    assertThat(order.isAsc()).isTrue();
                });
    }

    /** 排序字段来自固定白名单，客户端不能把任意列名拼进 ORDER BY。 */
    @Test
    void pageRejectsOrderFieldOutsideWhitelist() {
        UserQuery query = new UserQuery();
        query.setOrderBy("password");

        assertApiException(() -> service.page(query), HttpStatus.BAD_REQUEST, "VALIDATION_FAILED");

        verifyNoInteractions(userMapper, userRoleMapper, roleMapper);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pageTrimsKeywordAndPassesFiltersThrough() {
        UserQuery query = new UserQuery();
        query.setKeyword("  李  ");
        query.setStatus(IamUserStatus.DISABLED);
        query.setRoleId(EMPLOYEE_ROLE_ID);
        stubPage(query, List.of(), 0);

        service.page(query);

        verify(userMapper).selectUserPage(any(Page.class), eq("李"),
                eq(IamUserStatus.DISABLED), eq(EMPLOYEE_ROLE_ID));
    }

    /** 空白关键字按"没有筛选"处理，而不是把空格拼进 LIKE。 */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void pageTreatsBlankKeywordAsAbsent() {
        UserQuery query = new UserQuery();
        query.setKeyword("   ");
        stubPage(query, List.of(), 0);

        service.page(query);

        verify(userMapper).selectUserPage(any(Page.class), isNull(), isNull(), isNull());
    }

    @Test
    void pageReturnsEmptyResultWithoutQueryingRolesOrRoleTable() {
        UserQuery query = new UserQuery();
        stubPage(query, List.of(), 0);

        PageResult<UserResult> result = service.page(query);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isZero();
        verifyNoInteractions(userRoleMapper, roleMapper);
    }

    @Test
    void pageLeavesRolesEmptyForUsersWithoutAnyGrant() {
        UserQuery query = new UserQuery();
        stubPage(query, List.of(user(IamUserStatus.ENABLED, 0L)), 1);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        PageResult<UserResult> result = service.page(query);

        assertThat(result.items()).singleElement()
                .satisfies(item -> assertThat(item.roles()).isEmpty());
        verifyNoInteractions(roleMapper);
    }

    @Test
    void pageGroupsRolesPerUserAndLoadsTheRoleTableOnce() {
        UserQuery query = new UserQuery();
        IamUser first = user(USER_ID, "employee", IamUserStatus.ENABLED, 0L);
        IamUser second = user(7L, "it", IamUserStatus.ENABLED, 0L);
        stubPage(query, List.of(first, second), 2);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                grant(USER_ID, EMPLOYEE_ROLE_ID),
                grant(USER_ID, SUPPORT_ROLE_ID),
                grant(7L, SUPPORT_ROLE_ID)));
        when(roleMapper.selectBatchIds(List.of(EMPLOYEE_ROLE_ID, SUPPORT_ROLE_ID)))
                .thenReturn(List.of(role(SUPPORT_ROLE_ID, "IT_SUPPORT"), role(EMPLOYEE_ROLE_ID, "EMPLOYEE")));

        PageResult<UserResult> result = service.page(query);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().getFirst().roles())
                .extracting(summary -> summary.code())
                .containsExactly("EMPLOYEE", "IT_SUPPORT");
        assertThat(result.items().get(1).roles())
                .extracting(summary -> summary.code())
                .containsExactly("IT_SUPPORT");
        verify(roleMapper, times(1)).selectBatchIds(any());
    }

    // ---------- getById ----------

    @Test
    void getByIdRejectsNonPositiveIdWithoutQueryingDatabase() {
        assertApiException(() -> service.getById(0), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");

        verifyNoInteractions(userMapper, userRoleMapper, roleMapper);
    }

    @Test
    void getByIdReturnsNotFoundWhenUserDoesNotExist() {
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        assertApiException(() -> service.getById(USER_ID), HttpStatus.NOT_FOUND, "USER_NOT_FOUND");

        verifyNoInteractions(userRoleMapper, roleMapper);
    }

    @Test
    void getByIdReturnsZeroRoleUserWithoutQueryingRoleTable() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 0L));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        UserResult result = service.getById(USER_ID);

        assertThat(result.id()).isEqualTo(USER_ID);
        assertThat(result.username()).isEqualTo("employee");
        assertThat(result.roles()).isEmpty();
        assertThat(result.status()).isEqualTo(IamUserStatus.ENABLED);
        assertThat(result.createdAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        verifyNoInteractions(roleMapper);
    }

    @Test
    void getByIdReturnsRolesInRoleIdAscendingOrder() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 0L));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(
                grant(USER_ID, EMPLOYEE_ROLE_ID),
                grant(USER_ID, SUPPORT_ROLE_ID)));
        when(roleMapper.selectBatchIds(List.of(EMPLOYEE_ROLE_ID, SUPPORT_ROLE_ID)))
                .thenReturn(List.of(role(SUPPORT_ROLE_ID, "IT_SUPPORT"), role(EMPLOYEE_ROLE_ID, "EMPLOYEE")));

        UserResult result = service.getById(USER_ID);

        assertThat(result.roles()).extracting(summary -> summary.id())
                .containsExactly(EMPLOYEE_ROLE_ID, SUPPORT_ROLE_ID);
        assertThat(result.roles()).extracting(summary -> summary.code())
                .containsExactly("EMPLOYEE", "IT_SUPPORT");
    }

    // ---------- create ----------

    @Test
    void createRejectsDuplicateUsernameBeforeEncodingPasswordOrTouchingRoles() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertApiException(
                () -> service.create(new CreateUserCommand("employee", "员工", "Password#2026", null)),
                HttpStatus.CONFLICT,
                "USERNAME_CONFLICT");

        verifyNoInteractions(passwordEncoder, roleMapper);
        verify(userMapper, never()).insert(any(IamUser.class));
    }

    /** 预检查挡不住并发，唯一索引兜底后仍要落回同一个错误码。 */
    @Test
    void createConvertsDuplicateKeyFailureToUsernameConflict() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn(ENCODED_PASSWORD);
        doThrow(new DuplicateKeyException("duplicate")).when(userMapper).insert(any(IamUser.class));

        assertApiException(
                () -> service.create(new CreateUserCommand("employee", "员工", "Password#2026", null)),
                HttpStatus.CONFLICT,
                "USERNAME_CONFLICT");
    }

    @Test
    void createAllowsZeroRolesAndSkipsOperatorLookup() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn(ENCODED_PASSWORD);
        doAnswer(invocation -> {
            invocation.getArgument(0, IamUser.class).setId(USER_ID);
            return 1;
        }).when(userMapper).insert(any(IamUser.class));

        UserResult result = service.create(
                new CreateUserCommand(" employee ", " 员工 ", "Password#2026", null));

        assertThat(result.id()).isEqualTo(USER_ID);
        assertThat(result.username()).isEqualTo("employee");
        assertThat(result.displayName()).isEqualTo("员工");
        assertThat(result.status()).isEqualTo(IamUserStatus.ENABLED);
        assertThat(result.version()).isZero();
        assertThat(result.roles()).isEmpty();
        assertThat(result.createdAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        verifyNoInteractions(roleMapper, currentOperatorPort);
    }

    /** Argon2id 很慢，必须在取得角色悲观锁之前完成编码，避免长时间持锁。 */
    @Test
    void createEncodesPasswordBeforeLockingRoles() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn(ENCODED_PASSWORD);
        when(roleMapper.selectByIdsForUpdate(List.of(EMPLOYEE_ROLE_ID)))
                .thenReturn(List.of(role(EMPLOYEE_ROLE_ID, "EMPLOYEE")));
        when(currentOperatorPort.currentUserId()).thenReturn(OPERATOR_ID);
        doAnswer(invocation -> {
            invocation.getArgument(0, IamUser.class).setId(USER_ID);
            return 1;
        }).when(userMapper).insert(any(IamUser.class));

        service.create(new CreateUserCommand("employee", "员工", "Password#2026",
                List.of(EMPLOYEE_ROLE_ID)));

        InOrder order = inOrder(passwordEncoder, roleMapper, userMapper);
        order.verify(passwordEncoder).encode("Password#2026");
        order.verify(roleMapper).selectByIdsForUpdate(List.of(EMPLOYEE_ROLE_ID));
        order.verify(userMapper).insert(any(IamUser.class));
    }

    @Test
    void createNormalisesRoleIdsAndWritesGrantAuditColumns() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn(ENCODED_PASSWORD);
        when(roleMapper.selectByIdsForUpdate(List.of(EMPLOYEE_ROLE_ID, SUPPORT_ROLE_ID)))
                .thenReturn(List.of(role(EMPLOYEE_ROLE_ID, "EMPLOYEE"), role(SUPPORT_ROLE_ID, "IT_SUPPORT")));
        when(currentOperatorPort.currentUserId()).thenReturn(OPERATOR_ID);
        doAnswer(invocation -> {
            invocation.getArgument(0, IamUser.class).setId(USER_ID);
            return 1;
        }).when(userMapper).insert(any(IamUser.class));

        UserResult result = service.create(new CreateUserCommand("employee", "员工", "Password#2026",
                List.of(SUPPORT_ROLE_ID, EMPLOYEE_ROLE_ID, SUPPORT_ROLE_ID)));

        // 去重并升序后加锁：锁顺序必须可预测
        verify(roleMapper).selectByIdsForUpdate(List.of(EMPLOYEE_ROLE_ID, SUPPORT_ROLE_ID));
        assertThat(result.roles()).extracting(summary -> summary.id())
                .containsExactly(EMPLOYEE_ROLE_ID, SUPPORT_ROLE_ID);

        ArgumentCaptor<IamUserRole> grants = ArgumentCaptor.forClass(IamUserRole.class);
        verify(userRoleMapper, times(2)).insert(grants.capture());
        assertThat(grants.getAllValues())
                .allSatisfy(grant -> {
                    assertThat(grant.getUserId()).isEqualTo(USER_ID);
                    assertThat(grant.getGrantedBy()).isEqualTo(OPERATOR_ID);
                    assertThat(grant.getGrantedAt())
                            .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
                });
    }

    @Test
    void createRejectsWholeRequestWhenAnyRoleIsMissing() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn(ENCODED_PASSWORD);
        when(roleMapper.selectByIdsForUpdate(List.of(EMPLOYEE_ROLE_ID, 99L)))
                .thenReturn(List.of(role(EMPLOYEE_ROLE_ID, "EMPLOYEE")));

        assertApiException(
                () -> service.create(new CreateUserCommand("employee", "员工", "Password#2026",
                        List.of(EMPLOYEE_ROLE_ID, 99L))),
                HttpStatus.NOT_FOUND,
                "ROLE_NOT_FOUND");

        verify(userMapper, never()).insert(any(IamUser.class));
        verify(userRoleMapper, never()).insert(any(IamUserRole.class));
    }

    @Test
    void createConvertsRoleLockFailureToRbacConflict() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn(ENCODED_PASSWORD);
        doThrow(new PessimisticLockingFailureException("lock wait timeout"))
                .when(roleMapper).selectByIdsForUpdate(List.of(EMPLOYEE_ROLE_ID));

        assertApiException(
                () -> service.create(new CreateUserCommand("employee", "员工", "Password#2026",
                        List.of(EMPLOYEE_ROLE_ID))),
                HttpStatus.CONFLICT,
                "RBAC_CONFLICT");

        verify(userMapper, never()).insert(any(IamUser.class));
    }

    // ---------- update ----------

    @Test
    void updateRejectsNonPositiveIdWithoutQueryingDatabase() {
        assertApiException(
                () -> service.update(0, new UpdateUserCommand("新名称", 0L)),
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND");

        verifyNoInteractions(userMapper, userRoleMapper, roleMapper);
    }

    @Test
    void updateReportsNotFoundWhenConditionalUpdateAffectsNoRowAndUserIsGone() {
        when(userMapper.update(isNull(), any())).thenReturn(0);
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        assertApiException(
                () -> service.update(USER_ID, new UpdateUserCommand("新名称", 3L)),
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND");
    }

    @Test
    void updateReportsVersionConflictWhenUserStillExists() {
        when(userMapper.update(isNull(), any())).thenReturn(0);
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 4L));

        assertApiException(
                () -> service.update(USER_ID, new UpdateUserCommand("新名称", 3L)),
                HttpStatus.CONFLICT,
                "USER_CONFLICT");
    }

    @Test
    void updateReturnsReloadedUserOnSuccess() {
        when(userMapper.update(isNull(), any())).thenReturn(1);
        IamUser reloaded = user(IamUserStatus.ENABLED, 4L);
        reloaded.setDisplayName("新名称");
        when(userMapper.selectById(USER_ID)).thenReturn(reloaded);
        when(userRoleMapper.selectList(any())).thenReturn(List.of(grant(USER_ID, EMPLOYEE_ROLE_ID)));
        when(roleMapper.selectBatchIds(List.of(EMPLOYEE_ROLE_ID)))
                .thenReturn(List.of(role(EMPLOYEE_ROLE_ID, "EMPLOYEE")));

        UserResult result = service.update(USER_ID, new UpdateUserCommand(" 新名称 ", 3L));

        assertThat(result.displayName()).isEqualTo("新名称");
        assertThat(result.version()).isEqualTo(4L);
        assertThat(result.roles()).extracting(summary -> summary.code()).containsExactly("EMPLOYEE");
    }

    // ---------- enable ----------

    @Test
    void enableRejectsNonPositiveIdWithoutQueryingDatabase() {
        assertApiException(() -> service.enable(0, new UserStatusChangeCommand(0L)),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");

        verifyNoInteractions(userMapper, userRoleMapper, roleMapper, sessionRevocationPort);
    }

    @Test
    void enableReturnsNotFoundWhenUserDoesNotExist() {
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        assertApiException(() -> service.enable(USER_ID, new UserStatusChangeCommand(0L)),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");

        verify(userMapper, never()).update(any(), any());
        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void enableRejectsStaleVersion() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.DISABLED, 2L));

        assertApiException(() -> service.enable(USER_ID, new UserStatusChangeCommand(1L)),
                HttpStatus.CONFLICT, "USER_CONFLICT");

        verify(userMapper, never()).update(any(), any());
        verifyNoInteractions(sessionRevocationPort);
    }

    /** 已经启用时保持幂等：不改版本，也不撤销会话（否则会把用户无谓地踢下线）。 */
    @Test
    void enableIsIdempotentForAlreadyEnabledUser() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 2L));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        UserResult result = service.enable(USER_ID, new UserStatusChangeCommand(2L));

        assertThat(result.status()).isEqualTo(IamUserStatus.ENABLED);
        assertThat(result.version()).isEqualTo(2L);
        verify(userMapper, never()).update(any(), any());
        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void enableRevokesEverySessionAfterStatusChange() {
        when(userMapper.selectById(USER_ID))
                .thenReturn(user(IamUserStatus.DISABLED, 2L), user(IamUserStatus.ENABLED, 3L));
        when(userMapper.update(isNull(), any())).thenReturn(1);
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        UserResult result = service.enable(USER_ID, new UserStatusChangeCommand(2L));

        assertThat(result.status()).isEqualTo(IamUserStatus.ENABLED);
        assertThat(result.version()).isEqualTo(3L);
        verify(sessionRevocationPort, times(1)).revokeAll(USER_ID);
    }

    @Test
    void enableConvertsLostConditionalUpdateToUserConflict() {
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.DISABLED, 2L));
        when(userMapper.update(isNull(), any())).thenReturn(0);

        assertApiException(() -> service.enable(USER_ID, new UserStatusChangeCommand(2L)),
                HttpStatus.CONFLICT, "USER_CONFLICT");

        verifyNoInteractions(sessionRevocationPort);
    }

    // ---------- disable ----------

    /** 锁顺序固定为角色 → 用户，"最后启用管理员"的计数才有串行化保护。 */
    @Test
    void disableLocksSystemAdminRoleBeforeTargetUser() {
        when(roleMapper.selectByCodeForUpdate(SYSTEM_ADMIN))
                .thenReturn(role(SYSTEM_ADMIN_ROLE_ID, SYSTEM_ADMIN));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 0L));
        when(userRoleMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.update(isNull(), any())).thenReturn(1);
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.DISABLED, 1L));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        service.disable(USER_ID, new UserStatusChangeCommand(0L));

        InOrder order = inOrder(roleMapper, userMapper);
        order.verify(roleMapper).selectByCodeForUpdate(SYSTEM_ADMIN);
        order.verify(userMapper).selectByIdForUpdate(USER_ID);
    }

    @Test
    void disableReturnsNotFoundWhenLockedUserDoesNotExist() {
        when(roleMapper.selectByCodeForUpdate(SYSTEM_ADMIN)).thenReturn(null);
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(null);

        assertApiException(() -> service.disable(USER_ID, new UserStatusChangeCommand(0L)),
                HttpStatus.NOT_FOUND, "USER_NOT_FOUND");

        verify(userMapper, never()).update(any(), any());
        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void disableRejectsStaleVersion() {
        when(roleMapper.selectByCodeForUpdate(SYSTEM_ADMIN)).thenReturn(null);
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 3L));

        assertApiException(() -> service.disable(USER_ID, new UserStatusChangeCommand(2L)),
                HttpStatus.CONFLICT, "USER_CONFLICT");

        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void disableIsIdempotentForAlreadyDisabledUser() {
        when(roleMapper.selectByCodeForUpdate(SYSTEM_ADMIN)).thenReturn(null);
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.DISABLED, 2L));
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.DISABLED, 2L));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        UserResult result = service.disable(USER_ID, new UserStatusChangeCommand(2L));

        assertThat(result.status()).isEqualTo(IamUserStatus.DISABLED);
        verify(userMapper, never()).update(any(), any());
        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void disableProtectsLastEnabledAdministrator() {
        when(roleMapper.selectByCodeForUpdate(SYSTEM_ADMIN))
                .thenReturn(role(SYSTEM_ADMIN_ROLE_ID, SYSTEM_ADMIN));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 0L));
        when(userRoleMapper.selectCount(any())).thenReturn(1L);
        when(userRoleMapper.selectList(any()))
                .thenReturn(List.of(grant(USER_ID, SYSTEM_ADMIN_ROLE_ID)));
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertApiException(() -> service.disable(USER_ID, new UserStatusChangeCommand(0L)),
                HttpStatus.CONFLICT, "LAST_ADMIN_PROTECTED");

        verify(userMapper, never()).update(any(), any());
        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void disableAllowsAdministratorWhenAnotherEnabledOneRemains() {
        when(roleMapper.selectByCodeForUpdate(SYSTEM_ADMIN))
                .thenReturn(role(SYSTEM_ADMIN_ROLE_ID, SYSTEM_ADMIN));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 0L));
        when(userRoleMapper.selectCount(any())).thenReturn(1L);
        when(userRoleMapper.selectList(any()))
                .thenReturn(List.of(grant(USER_ID, SYSTEM_ADMIN_ROLE_ID)));
        when(userMapper.selectCount(any())).thenReturn(2L);
        when(userMapper.update(isNull(), any())).thenReturn(1);
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.DISABLED, 1L));
        // 同一个 selectList 桩同时服务"数出角色持有者"与成功后的详情回读，因此角色表也要备好
        when(roleMapper.selectBatchIds(List.of(SYSTEM_ADMIN_ROLE_ID)))
                .thenReturn(List.of(role(SYSTEM_ADMIN_ROLE_ID, SYSTEM_ADMIN)));

        service.disable(USER_ID, new UserStatusChangeCommand(0L));

        verify(sessionRevocationPort).revokeAll(USER_ID);
    }

    @Test
    void disableSkipsAdminProtectionWhenUserDoesNotHoldSystemAdmin() {
        when(roleMapper.selectByCodeForUpdate(SYSTEM_ADMIN))
                .thenReturn(role(SYSTEM_ADMIN_ROLE_ID, SYSTEM_ADMIN));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 0L));
        when(userRoleMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.update(isNull(), any())).thenReturn(1);
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.DISABLED, 1L));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        service.disable(USER_ID, new UserStatusChangeCommand(0L));

        verify(sessionRevocationPort).revokeAll(USER_ID);
    }

    @Test
    void disableConvertsLostConditionalUpdateToUserConflict() {
        when(roleMapper.selectByCodeForUpdate(SYSTEM_ADMIN)).thenReturn(null);
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 0L));
        when(userMapper.update(isNull(), any())).thenReturn(0);

        assertApiException(() -> service.disable(USER_ID, new UserStatusChangeCommand(0L)),
                HttpStatus.CONFLICT, "USER_CONFLICT");

        verifyNoInteractions(sessionRevocationPort);
    }

    // ---------- replaceRoles ----------

    @Test
    void replaceRolesRejectsNonPositiveIdWithoutQueryingDatabase() {
        assertApiException(
                () -> service.replaceRoles(0, new ReplaceUserRolesCommand(0L, List.of())),
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND");

        verifyNoInteractions(userMapper, userRoleMapper, roleMapper, sessionRevocationPort);
    }

    /** SYSTEM_ADMIN 即使不在目标集合里也必须一起加锁，否则"最后启用管理员"的计数没有互斥。 */
    @Test
    void replaceRolesAlwaysLocksSystemAdminRoleTogetherWithTargetRoles() {
        when(roleMapper.selectOne(any()))
                .thenReturn(role(SYSTEM_ADMIN_ROLE_ID, SYSTEM_ADMIN));
        when(roleMapper.selectByIdsForUpdate(List.of(SYSTEM_ADMIN_ROLE_ID, SUPPORT_ROLE_ID)))
                .thenReturn(List.of(role(SYSTEM_ADMIN_ROLE_ID, SYSTEM_ADMIN),
                        role(SUPPORT_ROLE_ID, "IT_SUPPORT")));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 0L));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID)).thenReturn(List.of());
        when(userMapper.update(isNull(), any())).thenReturn(1);
        when(currentOperatorPort.currentUserId()).thenReturn(OPERATOR_ID);
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 1L));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        service.replaceRoles(USER_ID, new ReplaceUserRolesCommand(0L, List.of(SUPPORT_ROLE_ID)));

        verify(roleMapper).selectByIdsForUpdate(List.of(SYSTEM_ADMIN_ROLE_ID, SUPPORT_ROLE_ID));
    }

    @Test
    void replaceRolesRejectsWholeRequestWhenTargetRoleIsMissing() {
        when(roleMapper.selectOne(any())).thenReturn(null);
        when(roleMapper.selectByIdsForUpdate(List.of(99L))).thenReturn(List.of());

        assertApiException(
                () -> service.replaceRoles(USER_ID, new ReplaceUserRolesCommand(0L, List.of(99L))),
                HttpStatus.NOT_FOUND,
                "ROLE_NOT_FOUND");

        verify(userMapper, never()).selectByIdForUpdate(anyLong());
        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void replaceRolesReturnsNotFoundWhenLockedUserDoesNotExist() {
        when(roleMapper.selectOne(any())).thenReturn(null);
        when(roleMapper.selectByIdsForUpdate(List.of(EMPLOYEE_ROLE_ID)))
                .thenReturn(List.of(role(EMPLOYEE_ROLE_ID, "EMPLOYEE")));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(null);

        assertApiException(
                () -> service.replaceRoles(USER_ID, new ReplaceUserRolesCommand(0L, List.of(EMPLOYEE_ROLE_ID))),
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND");

        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void replaceRolesRejectsStaleVersionBeforeWritingAnything() {
        when(roleMapper.selectOne(any())).thenReturn(null);
        when(roleMapper.selectByIdsForUpdate(List.of(EMPLOYEE_ROLE_ID)))
                .thenReturn(List.of(role(EMPLOYEE_ROLE_ID, "EMPLOYEE")));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 5L));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(grant(USER_ID, SUPPORT_ROLE_ID)));

        assertApiException(
                () -> service.replaceRoles(USER_ID,
                        new ReplaceUserRolesCommand(4L, List.of(EMPLOYEE_ROLE_ID))),
                HttpStatus.CONFLICT,
                "USER_CONFLICT");

        verify(userMapper, never()).update(any(), any());
        verify(userRoleMapper, never()).insert(any(IamUserRole.class));
        verifyNoInteractions(sessionRevocationPort);
    }

    /** 目标集合与现状一致时不写库、不撤会话，也不推进版本。 */
    @Test
    void replaceRolesIsIdempotentWhenSetIsUnchanged() {
        when(roleMapper.selectOne(any())).thenReturn(null);
        when(roleMapper.selectByIdsForUpdate(List.of(EMPLOYEE_ROLE_ID, SUPPORT_ROLE_ID)))
                .thenReturn(List.of(role(EMPLOYEE_ROLE_ID, "EMPLOYEE"), role(SUPPORT_ROLE_ID, "IT_SUPPORT")));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 0L));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(grant(USER_ID, EMPLOYEE_ROLE_ID), grant(USER_ID, SUPPORT_ROLE_ID)));
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 0L));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        service.replaceRoles(USER_ID,
                new ReplaceUserRolesCommand(0L, List.of(SUPPORT_ROLE_ID, EMPLOYEE_ROLE_ID)));

        verify(userMapper, never()).update(any(), any());
        verify(userRoleMapper, never()).insert(any(IamUserRole.class));
        verify(userRoleMapper, never()).delete(any());
        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void replaceRolesAddsMissingGrantsWithAuditColumns() {
        when(roleMapper.selectOne(any())).thenReturn(null);
        when(roleMapper.selectByIdsForUpdate(List.of(EMPLOYEE_ROLE_ID, SUPPORT_ROLE_ID)))
                .thenReturn(List.of(role(EMPLOYEE_ROLE_ID, "EMPLOYEE"), role(SUPPORT_ROLE_ID, "IT_SUPPORT")));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 0L));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(grant(USER_ID, EMPLOYEE_ROLE_ID)));
        when(userMapper.update(isNull(), any())).thenReturn(1);
        when(currentOperatorPort.currentUserId()).thenReturn(OPERATOR_ID);
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 1L));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        service.replaceRoles(USER_ID,
                new ReplaceUserRolesCommand(0L, List.of(EMPLOYEE_ROLE_ID, SUPPORT_ROLE_ID)));

        // 已存在的 EMPLOYEE 关系保持原审计字段不动
        verify(userRoleMapper, never()).delete(any());
        ArgumentCaptor<IamUserRole> inserted = ArgumentCaptor.forClass(IamUserRole.class);
        verify(userRoleMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getRoleId()).isEqualTo(SUPPORT_ROLE_ID);
        assertThat(inserted.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(inserted.getValue().getGrantedBy()).isEqualTo(OPERATOR_ID);
        assertThat(inserted.getValue().getGrantedAt())
                .isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        verify(sessionRevocationPort).revokeAll(USER_ID);
    }

    @Test
    void replaceRolesRemovesGrantsMissingFromTargetAndRevokesSessionsOnce() {
        when(roleMapper.selectOne(any())).thenReturn(null);
        when(roleMapper.selectByIdsForUpdate(List.of(EMPLOYEE_ROLE_ID)))
                .thenReturn(List.of(role(EMPLOYEE_ROLE_ID, "EMPLOYEE")));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 0L));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(grant(USER_ID, EMPLOYEE_ROLE_ID), grant(USER_ID, SUPPORT_ROLE_ID)));
        when(userMapper.update(isNull(), any())).thenReturn(1);
        when(userRoleMapper.delete(any())).thenReturn(1);
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 1L));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(grant(USER_ID, EMPLOYEE_ROLE_ID)));
        when(roleMapper.selectBatchIds(List.of(EMPLOYEE_ROLE_ID)))
                .thenReturn(List.of(role(EMPLOYEE_ROLE_ID, "EMPLOYEE")));

        service.replaceRoles(USER_ID, new ReplaceUserRolesCommand(0L, List.of(EMPLOYEE_ROLE_ID)));

        verify(userRoleMapper).delete(any());
        verify(userRoleMapper, never()).insert(any(IamUserRole.class));
        verify(sessionRevocationPort, times(1)).revokeAll(USER_ID);
    }

    /** 清空到零角色是合法终态（2026-09-22 确认），但同样要撤销会话。 */
    @Test
    void replaceRolesAllowsEmptySetAsZeroRoleEndState() {
        when(roleMapper.selectOne(any())).thenReturn(null);
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 0L));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(grant(USER_ID, EMPLOYEE_ROLE_ID)));
        when(userMapper.update(isNull(), any())).thenReturn(1);
        when(userRoleMapper.delete(any())).thenReturn(1);
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 1L));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        service.replaceRoles(USER_ID, new ReplaceUserRolesCommand(0L, List.of()));

        verify(roleMapper, never()).selectByIdsForUpdate(any());
        verify(userRoleMapper).delete(any());
        verify(sessionRevocationPort).revokeAll(USER_ID);
    }

    @Test
    void replaceRolesConvertsDeleteRowMismatchToRbacConflict() {
        when(roleMapper.selectOne(any())).thenReturn(null);
        when(roleMapper.selectByIdsForUpdate(List.of(EMPLOYEE_ROLE_ID)))
                .thenReturn(List.of(role(EMPLOYEE_ROLE_ID, "EMPLOYEE")));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 0L));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(grant(USER_ID, EMPLOYEE_ROLE_ID), grant(USER_ID, SUPPORT_ROLE_ID)));
        when(userMapper.update(isNull(), any())).thenReturn(1);
        when(userRoleMapper.delete(any())).thenReturn(0);

        assertApiException(
                () -> service.replaceRoles(USER_ID,
                        new ReplaceUserRolesCommand(0L, List.of(EMPLOYEE_ROLE_ID))),
                HttpStatus.CONFLICT,
                "RBAC_CONFLICT");

        verify(userRoleMapper, never()).insert(any(IamUserRole.class));
        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void replaceRolesProtectsLastEnabledAdministrator() {
        when(roleMapper.selectOne(any())).thenReturn(role(SYSTEM_ADMIN_ROLE_ID, SYSTEM_ADMIN));
        when(roleMapper.selectByIdsForUpdate(List.of(SYSTEM_ADMIN_ROLE_ID)))
                .thenReturn(List.of(role(SYSTEM_ADMIN_ROLE_ID, SYSTEM_ADMIN)));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 0L));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(grant(USER_ID, SYSTEM_ADMIN_ROLE_ID)));
        when(userRoleMapper.selectList(any()))
                .thenReturn(List.of(grant(USER_ID, SYSTEM_ADMIN_ROLE_ID)));
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertApiException(
                () -> service.replaceRoles(USER_ID, new ReplaceUserRolesCommand(0L, List.of())),
                HttpStatus.CONFLICT,
                "LAST_ADMIN_PROTECTED");

        verify(userMapper, never()).update(any(), any());
        verify(userRoleMapper, never()).delete(any());
        verifyNoInteractions(sessionRevocationPort);
    }

    /** 已被停用的管理员不再计入"启用管理员"，因此可以移除其 SYSTEM_ADMIN 角色。 */
    @Test
    void replaceRolesAllowsRemovingAdminRoleFromDisabledUser() {
        when(roleMapper.selectOne(any())).thenReturn(role(SYSTEM_ADMIN_ROLE_ID, SYSTEM_ADMIN));
        when(roleMapper.selectByIdsForUpdate(List.of(SYSTEM_ADMIN_ROLE_ID)))
                .thenReturn(List.of(role(SYSTEM_ADMIN_ROLE_ID, SYSTEM_ADMIN)));
        when(userMapper.selectByIdForUpdate(USER_ID)).thenReturn(user(IamUserStatus.DISABLED, 0L));
        when(userRoleMapper.selectByUserIdForUpdate(USER_ID))
                .thenReturn(List.of(grant(USER_ID, SYSTEM_ADMIN_ROLE_ID)));
        when(userMapper.update(isNull(), any())).thenReturn(1);
        when(userRoleMapper.delete(any())).thenReturn(1);
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.DISABLED, 1L));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        service.replaceRoles(USER_ID, new ReplaceUserRolesCommand(0L, List.of()));

        verify(userRoleMapper).delete(any());
        verify(sessionRevocationPort).revokeAll(USER_ID);
    }

    @Test
    void replaceRolesConvertsLockTimeoutToRbacConflict() {
        doThrow(new PessimisticLockingFailureException("lock wait timeout"))
                .when(roleMapper).selectByIdsForUpdate(List.of(EMPLOYEE_ROLE_ID));
        when(roleMapper.selectOne(any())).thenReturn(null);

        assertApiException(
                () -> service.replaceRoles(USER_ID,
                        new ReplaceUserRolesCommand(0L, List.of(EMPLOYEE_ROLE_ID))),
                HttpStatus.CONFLICT,
                "RBAC_CONFLICT");

        verifyNoInteractions(sessionRevocationPort);
    }

    // ---------- resetPassword ----------

    @Test
    void resetPasswordRejectsNonPositiveIdWithoutEncodingPassword() {
        assertApiException(
                () -> service.resetPassword(0, new ResetUserPasswordCommand(0L, "Password#2026")),
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND");

        verifyNoInteractions(passwordEncoder, userMapper, sessionRevocationPort);
    }

    @Test
    void resetPasswordReportsNotFoundWhenConditionalUpdateAffectsNoRowAndUserIsGone() {
        when(passwordEncoder.encode(anyString())).thenReturn(ENCODED_PASSWORD);
        when(userMapper.update(isNull(), any())).thenReturn(0);
        when(userMapper.selectById(USER_ID)).thenReturn(null);

        assertApiException(
                () -> service.resetPassword(USER_ID, new ResetUserPasswordCommand(0L, "Password#2026")),
                HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND");

        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void resetPasswordReportsVersionConflictWhenUserStillExists() {
        when(passwordEncoder.encode(anyString())).thenReturn(ENCODED_PASSWORD);
        when(userMapper.update(isNull(), any())).thenReturn(0);
        when(userMapper.selectById(USER_ID)).thenReturn(user(IamUserStatus.ENABLED, 5L));

        assertApiException(
                () -> service.resetPassword(USER_ID, new ResetUserPasswordCommand(4L, "Password#2026")),
                HttpStatus.CONFLICT,
                "USER_CONFLICT");

        verifyNoInteractions(sessionRevocationPort);
    }

    @Test
    void resetPasswordWritesEncodedPasswordThenRevokesEverySession() {
        when(passwordEncoder.encode("Password#2026")).thenReturn(ENCODED_PASSWORD);
        when(userMapper.update(isNull(), any())).thenReturn(1);

        service.resetPassword(USER_ID, new ResetUserPasswordCommand(0L, "Password#2026"));

        InOrder order = inOrder(passwordEncoder, userMapper, sessionRevocationPort);
        order.verify(passwordEncoder).encode("Password#2026");
        order.verify(userMapper).update(isNull(), any());
        // 先撤 Redis（旧票立即失效）再提交 MySQL，与改密口径一致
        order.verify(sessionRevocationPort).revokeAll(USER_ID);
    }

    // ---------- 事务边界 ----------

    @Test
    void writeMethodsDeclareTransactionBoundaries() throws NoSuchMethodException {
        assertThat(IamUserServiceImpl.class
                .getMethod("create", CreateUserCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamUserServiceImpl.class
                .getMethod("update", long.class, UpdateUserCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamUserServiceImpl.class
                .getMethod("enable", long.class, UserStatusChangeCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamUserServiceImpl.class
                .getMethod("disable", long.class, UserStatusChangeCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamUserServiceImpl.class
                .getMethod("replaceRoles", long.class, ReplaceUserRolesCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(IamUserServiceImpl.class
                .getMethod("resetPassword", long.class, ResetUserPasswordCommand.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    // ---------- 辅助 ----------

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void stubPage(UserQuery query, List<IamUser> records, long total) {
        doAnswer(invocation -> {
            Page<IamUser> requested = invocation.getArgument(0);
            requested.setRecords(records);
            requested.setTotal(total);
            return requested;
        }).when(userMapper).selectUserPage(any(Page.class), any(), any(), any());
    }

    private static IamUser user(IamUserStatus status, long version) {
        return user(USER_ID, "employee", status, version);
    }

    private static IamUser user(long id, String username, IamUserStatus status, long version) {
        IamUser user = new IamUser();
        user.setId(id);
        user.setUsername(username);
        user.setDisplayName("演示员工");
        user.setPassword(ENCODED_PASSWORD);
        user.setStatus(status);
        user.setVersion(version);
        user.setCreatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        user.setUpdatedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
        return user;
    }

    private static IamUserRole grant(long userId, long roleId) {
        IamUserRole grant = new IamUserRole();
        grant.setUserId(userId);
        grant.setRoleId(roleId);
        return grant;
    }

    private static IamRole role(long roleId, String code) {
        IamRole role = new IamRole();
        role.setId(roleId);
        role.setCode(code);
        role.setName(code);
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
