package com.flowdesk.iam.application.service.impl;

import com.flowdesk.iam.application.result.AuthenticationSnapshot;
import com.flowdesk.iam.application.result.UserProfileSnapshot;
import com.flowdesk.iam.domain.IamPermission;
import com.flowdesk.iam.domain.IamRole;
import com.flowdesk.iam.domain.IamRolePermission;
import com.flowdesk.iam.domain.IamUser;
import com.flowdesk.iam.domain.IamUserRole;
import com.flowdesk.iam.domain.IamUserStatus;
import com.flowdesk.iam.mapper.IamPermissionMapper;
import com.flowdesk.iam.mapper.IamRoleMapper;
import com.flowdesk.iam.mapper.IamRolePermissionMapper;
import com.flowdesk.iam.mapper.IamUserMapper;
import com.flowdesk.iam.mapper.IamUserRoleMapper;
import com.flowdesk.support.MybatisPlusTestMetadata;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 认证读取模型的两条查询链：登录按登录名取授权快照，响应组装按用户 ID 取最新资料。
 *
 * <p>两者都被 {@code AuthServiceImpl} 直接调用，Web 测试只能覆盖到"整条链路成功或失败"；
 * 这里的用例固定逐层查询顺序与"零角色/零权限时不发 {@code IN ()}"这一类分支。</p>
 */
class IamAuthServiceImplTest {

    private static final long USER_ID = 42L;
    private static final String USERNAME = "employee";

    private final IamUserMapper userMapper = mock(IamUserMapper.class);
    private final IamUserRoleMapper userRoleMapper = mock(IamUserRoleMapper.class);
    private final IamRoleMapper roleMapper = mock(IamRoleMapper.class);
    private final IamRolePermissionMapper rolePermissionMapper = mock(IamRolePermissionMapper.class);
    private final IamPermissionMapper permissionMapper = mock(IamPermissionMapper.class);

    private final IamAuthServiceImpl service = new IamAuthServiceImpl(
            userMapper, userRoleMapper, roleMapper, rolePermissionMapper, permissionMapper);

    @BeforeAll
    static void initializeMybatisPlusMetadata() {
        MybatisPlusTestMetadata.initialize(
                IamUser.class, IamUserRole.class, IamRolePermission.class);
    }

    @Test
    void returnsNullWhenUsernameDoesNotExist() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertThat(service.findByUsername("nobody")).isNull();

        verifyNoInteractions(userRoleMapper, roleMapper, rolePermissionMapper, permissionMapper);
    }

    /**
     * 零角色必须在拼 {@code IN ()} 之前返回：空集合会让 MySQL 直接报语法错误，
     * 而"用户零角色"是 2026-09-22 起明确允许的终态。
     */
    @Test
    void returnsEmptyAuthorizationWithoutQueryingRolesWhenUserHasNoRole() {
        when(userMapper.selectOne(any())).thenReturn(user(IamUserStatus.ENABLED));
        when(userRoleMapper.selectList(any())).thenReturn(List.of());

        AuthenticationSnapshot snapshot = service.findByUsername(USERNAME);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.userId()).isEqualTo(USER_ID);
        assertThat(snapshot.username()).isEqualTo(USERNAME);
        assertThat(snapshot.status()).isEqualTo(IamUserStatus.ENABLED);
        assertThat(snapshot.roleCodes()).isEmpty();
        assertThat(snapshot.permissionCodes()).isEmpty();
        verifyNoInteractions(roleMapper, rolePermissionMapper, permissionMapper);
    }

    /** 角色一个权限都没有时同样不能去查权限表，否则又会出现空 {@code IN ()}。 */
    @Test
    void skipsPermissionLookupWhenNoRoleHasPermission() {
        when(userMapper.selectOne(any())).thenReturn(user(IamUserStatus.ENABLED));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(grant(10L)));
        when(roleMapper.selectByIds(List.of(10L))).thenReturn(List.of(role(10L, "EMPLOYEE")));
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of());

        AuthenticationSnapshot snapshot = service.findByUsername(USERNAME);

        assertThat(snapshot.roleCodes()).containsExactly("EMPLOYEE");
        assertThat(snapshot.permissionCodes()).isEmpty();
        verifyNoInteractions(permissionMapper);
    }

    @Test
    void returnsDistinctPermissionCodesInAscendingOrderAcrossRoles() {
        when(userMapper.selectOne(any())).thenReturn(user(IamUserStatus.ENABLED));
        when(userRoleMapper.selectList(any())).thenReturn(List.of(grant(10L), grant(20L)));
        when(roleMapper.selectByIds(List.of(10L, 20L)))
                .thenReturn(List.of(role(10L, "EMPLOYEE"), role(20L, "IT_SUPPORT")));
        // 两个角色都授予了 100：去重后只查一次，权限编码才不会重复
        when(rolePermissionMapper.selectList(any())).thenReturn(List.of(
                rolePermission(10L, 100L),
                rolePermission(20L, 100L),
                rolePermission(10L, 200L)));
        when(permissionMapper.selectByIds(List.of(100L, 200L))).thenReturn(List.of(
                permission(200L, "TICKET_VIEW"),
                permission(100L, "TICKET_CREATE")));

        AuthenticationSnapshot snapshot = service.findByUsername(USERNAME);

        assertThat(snapshot.roleCodes()).containsExactly("EMPLOYEE", "IT_SUPPORT");
        assertThat(snapshot.permissionCodes()).containsExactly("TICKET_CREATE", "TICKET_VIEW");
        verify(permissionMapper).selectByIds(List.of(100L, 200L));
    }

    @Test
    void findProfileByIdReturnsLatestDisplayName() {
        IamUser profile = user(IamUserStatus.ENABLED);
        profile.setDisplayName("改名后的员工");
        when(userMapper.selectProfileById(USER_ID)).thenReturn(profile);

        UserProfileSnapshot snapshot = service.findProfileById(USER_ID);

        assertThat(snapshot).isNotNull();
        assertThat(snapshot.userId()).isEqualTo(USER_ID);
        assertThat(snapshot.username()).isEqualTo(USERNAME);
        assertThat(snapshot.displayName()).isEqualTo("改名后的员工");
        assertThat(snapshot.status()).isEqualTo(IamUserStatus.ENABLED);
        verify(userMapper).selectProfileById(USER_ID);
    }

    @Test
    void findProfileByIdReturnsNullWhenUserDoesNotExist() {
        when(userMapper.selectProfileById(USER_ID)).thenReturn(null);

        assertThat(service.findProfileById(USER_ID)).isNull();
    }

    private static IamUser user(IamUserStatus status) {
        IamUser user = new IamUser();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        user.setDisplayName("演示员工");
        user.setPassword("encoded-password");
        user.setStatus(status);
        return user;
    }

    private static IamUserRole grant(long roleId) {
        IamUserRole grant = new IamUserRole();
        grant.setUserId(USER_ID);
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

    private static IamRolePermission rolePermission(long roleId, long permissionId) {
        IamRolePermission grant = new IamRolePermission();
        grant.setRoleId(roleId);
        grant.setPermissionId(permissionId);
        return grant;
    }

    private static IamPermission permission(long permissionId, String code) {
        IamPermission permission = new IamPermission();
        permission.setId(permissionId);
        permission.setCode(code);
        permission.setName(code);
        return permission;
    }
}
