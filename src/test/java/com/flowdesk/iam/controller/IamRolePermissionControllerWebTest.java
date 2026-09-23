package com.flowdesk.iam.controller;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.application.result.RolePermissionResult;
import com.flowdesk.iam.application.command.GrantRolePermissionsCommand;
import com.flowdesk.iam.application.query.RolePermissionQuery;
import com.flowdesk.iam.application.command.RevokeRolePermissionsCommand;
import com.flowdesk.iam.application.service.IamRolePermissionService;
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
 * 角色权限授权接口的 Web 契约测试。
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
class IamRolePermissionControllerWebTest {

    private static final String ROLE_PERMISSIONS = "/fd/v1/admin/role-permissions";
    private static final long ROLE_ID = 5L;
    private static final long PERMISSION_ID = 10L;
    private static final long OPERATOR_ID = 3L;
    private static final OffsetDateTime GRANTED_AT =
            OffsetDateTime.parse("2026-09-22T04:30:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IamRolePermissionService rolePermissionService;

    /** 排除 Redis 自动配置后，用替身满足认证模块的依赖。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    // ---------- 认证、鉴权矩阵 ----------

    @ParameterizedTest(name = "{0} without authentication returns 401")
    @MethodSource("rolePermissionRequests")
    void everyEndpointRequiresAuthentication(
            String endpoint, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(rolePermissionService);
    }

    @ParameterizedTest(name = "{0} without RBAC_MANAGE returns 403")
    @MethodSource("rolePermissionRequests")
    void everyEndpointRequiresRbacManage(
            String endpoint, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.with(user("employee")
                        .authorities(new SimpleGrantedAuthority("TICKET_CREATE"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(rolePermissionService);
    }

    static Stream<Arguments> rolePermissionRequests() {
        return Stream.of(
                Arguments.of("list role permissions", get(ROLE_PERMISSIONS)
                        .queryParam("roleId", String.valueOf(ROLE_ID))),
                Arguments.of("grant role permissions", post(ROLE_PERMISSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody())),
                Arguments.of("revoke role permission",
                        delete(ROLE_PERMISSIONS + "/" + ROLE_ID + "/" + PERMISSION_ID)),
                Arguments.of("batch revoke role permissions",
                        post(ROLE_PERMISSIONS + "/actions/revoke")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(grantBody())),
                Arguments.of("clear all role permissions",
                        delete(ROLE_PERMISSIONS + "/roles/" + ROLE_ID))
        );
    }

    // ---------- RBAC_MANAGE 成功路径 ----------

    @Test
    void rbacManagerCanListRolePermissionsAndFiltersReachService() throws Exception {
        when(rolePermissionService.page(any())).thenReturn(
                new PageResult<>(List.of(rolePermission()), 2, 10, 1, 1));

        mockMvc.perform(get(ROLE_PERMISSIONS)
                        .with(rbacManager())
                        .queryParam("pageNo", "2")
                        .queryParam("pageSize", "10")
                        .queryParam("roleId", String.valueOf(ROLE_ID))
                        .queryParam("orderBy", "permission_id")
                        .queryParam("orderDirection", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].roleId").value(ROLE_ID))
                .andExpect(jsonPath("$.data.items[0].roleCode").value("IT_SUPPORT"))
                .andExpect(jsonPath("$.data.items[0].permissionId").value(PERMISSION_ID))
                .andExpect(jsonPath("$.data.items[0].permissionCode").value("TICKET_VIEW"))
                .andExpect(jsonPath("$.data.items[0].permissionName").value("查看工单"))
                .andExpect(jsonPath("$.data.items[0].grantedBy").value(OPERATOR_ID));

        ArgumentCaptor<RolePermissionQuery> queryCaptor =
                ArgumentCaptor.forClass(RolePermissionQuery.class);
        verify(rolePermissionService).page(queryCaptor.capture());
        RolePermissionQuery query = queryCaptor.getValue();
        assertThat(query.getRoleId()).isEqualTo(ROLE_ID);
        assertThat(query.getPageNo()).isEqualTo(2);
        assertThat(query.getPageSize()).isEqualTo(10);
        assertThat(query.getOrderBy()).isEqualTo("permission_id");
        assertThat(query.getOrderDirection()).isEqualTo("desc");
    }

    @Test
    void rbacManagerCanListRolePermissionsByPermissionIdOnly() throws Exception {
        when(rolePermissionService.page(any())).thenReturn(
                new PageResult<>(List.of(rolePermission()), 1, 20, 1, 1));

        mockMvc.perform(get(ROLE_PERMISSIONS)
                        .with(rbacManager())
                        .queryParam("permissionId", String.valueOf(PERMISSION_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        ArgumentCaptor<RolePermissionQuery> queryCaptor =
                ArgumentCaptor.forClass(RolePermissionQuery.class);
        verify(rolePermissionService).page(queryCaptor.capture());
        assertThat(queryCaptor.getValue().getPermissionId()).isEqualTo(PERMISSION_ID);
        assertThat(queryCaptor.getValue().getRoleId()).isNull();
    }

    @Test
    void rbacManagerCanGrantRolePermissionsAndReceivesOrderedGrantList() throws Exception {
        when(rolePermissionService.grant(any())).thenReturn(List.of(rolePermission()));

        mockMvc.perform(post(ROLE_PERMISSIONS)
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data[0].roleId").value(ROLE_ID))
                .andExpect(jsonPath("$.data[0].permissionId").value(PERMISSION_ID));

        ArgumentCaptor<GrantRolePermissionsCommand> requestCaptor =
                ArgumentCaptor.forClass(GrantRolePermissionsCommand.class);
        verify(rolePermissionService).grant(requestCaptor.capture());
        assertThat(requestCaptor.getValue().roleId()).isEqualTo(ROLE_ID);
        assertThat(requestCaptor.getValue().permissionIds())
                .containsExactly(PERMISSION_ID, 11L);
    }

    @Test
    void rbacManagerCanRevokeSingleRolePermission() throws Exception {
        mockMvc.perform(delete(ROLE_PERMISSIONS + "/" + ROLE_ID + "/" + PERMISSION_ID)
                        .with(rbacManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(rolePermissionService).revoke(ROLE_ID, PERMISSION_ID);
    }

    @Test
    void rbacManagerCanBatchRevokeRolePermissions() throws Exception {
        mockMvc.perform(post(ROLE_PERMISSIONS + "/actions/revoke")
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());

        ArgumentCaptor<RevokeRolePermissionsCommand> requestCaptor =
                ArgumentCaptor.forClass(RevokeRolePermissionsCommand.class);
        verify(rolePermissionService).revokeBatch(requestCaptor.capture());
        assertThat(requestCaptor.getValue().roleId()).isEqualTo(ROLE_ID);
        assertThat(requestCaptor.getValue().permissionIds())
                .containsExactly(PERMISSION_ID, 11L);
    }

    @Test
    void rbacManagerCanClearAllRolePermissions() throws Exception {
        mockMvc.perform(delete(ROLE_PERMISSIONS + "/roles/" + ROLE_ID)
                        .with(rbacManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(rolePermissionService).clearAll(ROLE_ID);
    }

    // ---------- Web 入口校验 ----------

    @Test
    void listWithoutAnyFilterIsRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(get(ROLE_PERMISSIONS).with(rbacManager()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(rolePermissionService, never()).page(any());
    }

    @Test
    void invalidPageParametersAreRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(get(ROLE_PERMISSIONS)
                        .with(rbacManager())
                        .queryParam("roleId", String.valueOf(ROLE_ID))
                        .queryParam("pageNo", "0")
                        .queryParam("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(rolePermissionService, never()).page(any());
    }

    @Test
    void emptyPermissionIdListIsRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(post(ROLE_PERMISSIONS)
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roleId": 5,
                                  "permissionIds": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(rolePermissionService, never()).grant(any());
    }

    @Test
    void moreThanOneHundredPermissionIdsAreRejectedBeforeServiceInvocation() throws Exception {
        String tooManyIds = java.util.stream.LongStream.rangeClosed(1, 101)
                .mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));

        mockMvc.perform(post(ROLE_PERMISSIONS)
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roleId": 5,
                                  "permissionIds": [%s]
                                }
                                """.formatted(tooManyIds)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(rolePermissionService, never()).grant(any());
    }

    @Test
    void emptyPermissionIdListOnBatchRevokeIsRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(post(ROLE_PERMISSIONS + "/actions/revoke")
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "roleId": 5,
                                  "permissionIds": []
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(rolePermissionService, never()).revokeBatch(any());
    }

    private static RolePermissionResult rolePermission() {
        return new RolePermissionResult(
                ROLE_ID, "IT_SUPPORT", PERMISSION_ID, "TICKET_VIEW", "查看工单",
                OPERATOR_ID, GRANTED_AT);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor
    rbacManager() {
        return user("rbac-admin")
                .authorities(new SimpleGrantedAuthority("RBAC_MANAGE"));
    }

    private static String grantBody() {
        return """
                {
                  "roleId": 5,
                  "permissionIds": [10, 11]
                }
                """;
    }
}
