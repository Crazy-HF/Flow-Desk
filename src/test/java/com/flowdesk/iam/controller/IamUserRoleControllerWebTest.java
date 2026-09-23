package com.flowdesk.iam.controller;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.application.result.UserRoleResult;
import com.flowdesk.iam.application.command.GrantRoleToUsersCommand;
import com.flowdesk.iam.application.command.GrantUserRolesCommand;
import com.flowdesk.iam.application.query.UserRoleQuery;
import com.flowdesk.iam.application.command.RevokeUserRolesCommand;
import com.flowdesk.iam.application.service.IamUserRoleService;
import com.flowdesk.support.MockedPersistenceConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户角色授权接口的 Web 契约测试。
 *
 * <p>使用真实安全过滤链与方法级授权，服务替换为 mock：本类只验证 HTTP 状态、响应信封、
 * 请求绑定、入口校验与 {@code RBAC_MANAGE} 权限边界，不重复测试服务层业务规则。</p>
 */
@ActiveProfiles("test")
@SpringBootTest(
        classes = FlowDeskApplication.class,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration,"
                + "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration")
@AutoConfigureMockMvc
@Import(MockedPersistenceConfiguration.class)
class IamUserRoleControllerWebTest {

    private static final String USER_ROLES = "/fd/v1/admin/user-roles";
    private static final long USER_ID = 11L;
    private static final long ROLE_ID = 1L;
    private static final long OPERATOR_ID = 3L;
    private static final OffsetDateTime GRANTED_AT =
            OffsetDateTime.parse("2026-09-22T04:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IamUserRoleService userRoleService;

    /** 排除 Redis 自动配置后，用替身满足认证模块的依赖。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    // ---------- 认证、鉴权矩阵 ----------

    @ParameterizedTest(name = "{0} without authentication returns 401")
    @MethodSource("userRoleRequests")
    void everyEndpointRequiresAuthentication(
            String endpoint, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(userRoleService);
    }

    @ParameterizedTest(name = "{0} without RBAC_MANAGE returns 403")
    @MethodSource("userRoleRequests")
    void everyEndpointRequiresRbacManage(
            String endpoint, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.with(user("employee")
                        .authorities(new SimpleGrantedAuthority("TICKET_CREATE"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(userRoleService);
    }

    static Stream<Arguments> userRoleRequests() {
        return Stream.of(
                Arguments.of("list user roles", get(USER_ROLES)
                        .queryParam("userId", String.valueOf(USER_ID))),
                Arguments.of("grant user roles", post(USER_ROLES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody())),
                Arguments.of("revoke user role",
                        delete(USER_ROLES + "/" + USER_ID + "/" + ROLE_ID)),
                Arguments.of("batch revoke user roles", post(USER_ROLES + "/actions/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody())),
                Arguments.of("clear all user roles",
                        delete(USER_ROLES + "/users/" + USER_ID)),
                Arguments.of("grant one role to users", post(USER_ROLES + "/actions/grant-users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantUsersBody()))
        );
    }

    // ---------- RBAC_MANAGE 成功路径 ----------

    @Test
    void rbacManagerCanListUserRolesAndFiltersReachService() throws Exception {
        when(userRoleService.page(any())).thenReturn(
                new PageResult<>(List.of(userRole()), 2, 10, 1, 1));

        mockMvc.perform(get(USER_ROLES)
                        .with(rbacManager())
                        .queryParam("pageNo", "2")
                        .queryParam("pageSize", "10")
                        .queryParam("userId", String.valueOf(USER_ID))
                        .queryParam("orderBy", "granted_at")
                        .queryParam("orderDirection", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].userId").value(USER_ID))
                .andExpect(jsonPath("$.data.items[0].username").value("employee"))
                .andExpect(jsonPath("$.data.items[0].roleId").value(ROLE_ID))
                .andExpect(jsonPath("$.data.items[0].roleCode").value("EMPLOYEE"))
                .andExpect(jsonPath("$.data.items[0].roleName").value("员工"))
                .andExpect(jsonPath("$.data.items[0].grantedBy").value(OPERATOR_ID));

        ArgumentCaptor<UserRoleQuery> queryCaptor =
                ArgumentCaptor.forClass(UserRoleQuery.class);
        verify(userRoleService).page(queryCaptor.capture());
        UserRoleQuery query = queryCaptor.getValue();
        assertThat(query.getUserId()).isEqualTo(USER_ID);
        assertThat(query.getPageNo()).isEqualTo(2);
        assertThat(query.getPageSize()).isEqualTo(10);
        assertThat(query.getOrderBy()).isEqualTo("granted_at");
        assertThat(query.getOrderDirection()).isEqualTo("desc");
    }

    @Test
    void rbacManagerCanListUserRolesByRoleIdOnly() throws Exception {
        when(userRoleService.page(any())).thenReturn(
                new PageResult<>(List.of(userRole()), 1, 20, 1, 1));

        mockMvc.perform(get(USER_ROLES)
                        .with(rbacManager())
                        .queryParam("roleId", String.valueOf(ROLE_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        ArgumentCaptor<UserRoleQuery> queryCaptor =
                ArgumentCaptor.forClass(UserRoleQuery.class);
        verify(userRoleService).page(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getRoleId()).isEqualTo(ROLE_ID);
        assertThat(queryCaptor.getValue().getUserId()).isNull();
    }

    @Test
    void rbacManagerCanGrantUserRolesAndReceivesOrderedGrantList() throws Exception {
        when(userRoleService.grant(any())).thenReturn(List.of(userRole()));

        mockMvc.perform(post(USER_ROLES)
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data[0].userId").value(USER_ID))
                .andExpect(jsonPath("$.data[0].roleId").value(ROLE_ID));

        ArgumentCaptor<GrantUserRolesCommand> requestCaptor =
                ArgumentCaptor.forClass(GrantUserRolesCommand.class);
        verify(userRoleService).grant(requestCaptor.capture());
        assertThat(requestCaptor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(requestCaptor.getValue().roleIds()).containsExactly(ROLE_ID, 2L);
    }

    @Test
    void rbacManagerCanRevokeSingleUserRole() throws Exception {
        mockMvc.perform(delete(USER_ROLES + "/" + USER_ID + "/" + ROLE_ID)
                        .with(rbacManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(userRoleService).revoke(USER_ID, ROLE_ID);
    }

    @Test
    void rbacManagerCanBatchRevokeUserRoles() throws Exception {
        mockMvc.perform(post(USER_ROLES + "/actions/revoke")
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());

        ArgumentCaptor<RevokeUserRolesCommand> requestCaptor =
                ArgumentCaptor.forClass(RevokeUserRolesCommand.class);
        verify(userRoleService).revokeBatch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().userId()).isEqualTo(USER_ID);
        assertThat(requestCaptor.getValue().roleIds()).containsExactly(ROLE_ID, 2L);
    }

    @Test
    void rbacManagerCanClearAllUserRoles() throws Exception {
        mockMvc.perform(delete(USER_ROLES + "/users/" + USER_ID).with(rbacManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(userRoleService).clearAll(USER_ID);
    }

    @Test
    void rbacManagerCanGrantOneRoleToManyUsers() throws Exception {
        when(userRoleService.grantUsers(any())).thenReturn(List.of(userRole()));

        mockMvc.perform(post(USER_ROLES + "/actions/grant-users")
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantUsersBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data[0].userId").value(USER_ID))
                .andExpect(jsonPath("$.data[0].roleId").value(ROLE_ID));

        ArgumentCaptor<GrantRoleToUsersCommand> requestCaptor =
                ArgumentCaptor.forClass(GrantRoleToUsersCommand.class);
        verify(userRoleService).grantUsers(requestCaptor.capture());
        assertThat(requestCaptor.getValue().roleId()).isEqualTo(ROLE_ID);
        assertThat(requestCaptor.getValue().userIds()).containsExactly(USER_ID, 12L);
    }

    // ---------- Web 入口校验 ----------

    @Test
    void listWithoutAnyFilterIsRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(get(USER_ROLES).with(rbacManager()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(userRoleService, never()).page(any());
    }

    @Test
    void invalidPageParametersAreRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(get(USER_ROLES)
                        .with(rbacManager())
                        .queryParam("userId", String.valueOf(USER_ID))
                        .queryParam("pageNo", "0")
                        .queryParam("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(userRoleService, never()).page(any());
    }

    @Test
    void emptyRoleIdListIsRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(post(USER_ROLES)
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": 11,
                                  "roleIds": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(userRoleService, never()).grant(any());
    }

    @Test
    void nonPositiveUserIdIsRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(post(USER_ROLES)
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": 0,
                                  "roleIds": [1]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(userRoleService, never()).grant(any());
    }

    @Test
    void emptyRoleIdListOnBatchRevokeIsRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(post(USER_ROLES + "/actions/revoke")
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": 11,
                                  "roleIds": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(userRoleService, never()).revokeBatch(any());
    }

    @Test
    void emptyUserIdListOnGrantUsersIsRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(post(USER_ROLES + "/actions/grant-users")
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roleId": 1,
                                  "userIds": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(userRoleService, never()).grantUsers(any());
    }

    private static UserRoleResult userRole() {
        return new UserRoleResult(
                USER_ID, "employee", ROLE_ID, "EMPLOYEE", "员工", OPERATOR_ID, GRANTED_AT);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor
    rbacManager() {
        return user("rbac-admin")
                .authorities(new SimpleGrantedAuthority("RBAC_MANAGE"));
    }

    private static String grantBody() {
        return """
                {
                  "userId": 11,
                  "roleIds": [1, 2]
                }
                """;
    }

    private static String revokeBody() {
        return """
                {
                  "userId": 11,
                  "roleIds": [1, 2]
                }
                """;
    }

    private static String grantUsersBody() {
        return """
                {
                  "roleId": 1,
                  "userIds": [11, 12]
                }
                """;
    }
}
