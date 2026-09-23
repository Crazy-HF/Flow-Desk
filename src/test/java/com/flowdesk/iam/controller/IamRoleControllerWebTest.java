package com.flowdesk.iam.controller;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.application.result.RoleResult;
import com.flowdesk.iam.application.command.CreateRoleCommand;
import com.flowdesk.iam.application.query.RoleQuery;
import com.flowdesk.iam.application.command.UpdateRoleCommand;
import com.flowdesk.iam.application.service.IamRoleService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 角色管理接口的 Web 契约测试。
 *
 * <p>使用真实安全过滤链与方法级授权，角色服务则替换为 mock：这样本类只验证
 * HTTP 状态、响应信封、请求绑定以及 {@code RBAC_MANAGE} 权限边界，不重复测试服务层业务规则。</p>
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
class IamRoleControllerWebTest {

    private static final String ROLES = "/fd/v1/admin/roles";
    private static final long ROLE_ID = 7L;
    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse("2026-09-21T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IamRoleService roleService;

    /** 排除 Redis 自动配置后，用替身满足认证模块的依赖。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    // ---------- 认证、鉴权矩阵 ----------

    @ParameterizedTest(name = "{0} without authentication returns 401")
    @MethodSource("roleRequests")
    void everyEndpointRequiresAuthentication(
            String endpoint, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(roleService);
    }

    @ParameterizedTest(name = "{0} without RBAC_MANAGE returns 403")
    @MethodSource("roleRequests")
    void everyEndpointRequiresRbacManage(
            String endpoint, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.with(user("employee")
                        .authorities(new SimpleGrantedAuthority("TICKET_CREATE"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(roleService);
    }

    static Stream<Arguments> roleRequests() {
        return Stream.of(
                Arguments.of("list roles", get(ROLES)),
                Arguments.of("get role", get(ROLES + "/" + ROLE_ID)),
                Arguments.of("create role", post(ROLES)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody())),
                Arguments.of("update role", put(ROLES + "/" + ROLE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody())),
                Arguments.of("delete role", delete(ROLES + "/" + ROLE_ID))
        );
    }

    // ---------- RBAC_MANAGE 成功路径 ----------

    @Test
    void rbacManagerCanListRolesAndQueryParametersReachService() throws Exception {
        when(roleService.page(any())).thenReturn(
                new PageResult<>(List.of(role()), 2, 10, 1, 1));

        mockMvc.perform(get(ROLES)
                        .with(rbacManager())
                        .queryParam("pageNo", "2")
                        .queryParam("pageSize", "10")
                        .queryParam("keyword", "audit")
                        .queryParam("orderBy", "name")
                        .queryParam("orderDirection", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(ROLE_ID))
                .andExpect(jsonPath("$.data.items[0].code").value("AUDITOR"))
                .andExpect(jsonPath("$.data.items[0].permissionIds[0]").value(10));

        ArgumentCaptor<RoleQuery> queryCaptor =
                ArgumentCaptor.forClass(RoleQuery.class);
        verify(roleService).page(queryCaptor.capture());
        RoleQuery query = queryCaptor.getValue();
        assertThat(query.getPageNo()).isEqualTo(2);
        assertThat(query.getPageSize()).isEqualTo(10);
        assertThat(query.getKeyword()).isEqualTo("audit");
        assertThat(query.getOrderBy()).isEqualTo("name");
        assertThat(query.getOrderDirection()).isEqualTo("desc");
    }

    @Test
    void rbacManagerCanGetRole() throws Exception {
        when(roleService.getById(ROLE_ID)).thenReturn(role());

        mockMvc.perform(get(ROLES + "/" + ROLE_ID).with(rbacManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(ROLE_ID))
                .andExpect(jsonPath("$.data.code").value("AUDITOR"))
                .andExpect(jsonPath("$.data.name").value("审计员"));

        verify(roleService).getById(ROLE_ID);
    }

    @Test
    void rbacManagerCanCreateRoleAndReceives201() throws Exception {
        when(roleService.create(any())).thenReturn(role());

        mockMvc.perform(post(ROLES)
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(ROLE_ID))
                .andExpect(jsonPath("$.data.code").value("AUDITOR"));

        ArgumentCaptor<CreateRoleCommand> requestCaptor =
                ArgumentCaptor.forClass(CreateRoleCommand.class);
        verify(roleService).create(requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .isEqualTo(new CreateRoleCommand(
                        "AUDITOR", "审计员", "只读审计角色", List.of(10L, 20L)));
    }

    @Test
    void rbacManagerCanUpdateOnlyMutableRoleFields() throws Exception {
        RoleResult updated = new RoleResult(
                ROLE_ID, "AUDITOR", "高级审计员", null, CREATED_AT, List.of(10L));
        when(roleService.update(org.mockito.ArgumentMatchers.eq(ROLE_ID), any()))
                .thenReturn(updated);

        mockMvc.perform(put(ROLES + "/" + ROLE_ID)
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.code").value("AUDITOR"))
                .andExpect(jsonPath("$.data.name").value("高级审计员"));

        ArgumentCaptor<UpdateRoleCommand> requestCaptor =
                ArgumentCaptor.forClass(UpdateRoleCommand.class);
        verify(roleService).update(org.mockito.ArgumentMatchers.eq(ROLE_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .isEqualTo(new UpdateRoleCommand("高级审计员", null));
    }

    @Test
    void rbacManagerCanDeleteRole() throws Exception {
        mockMvc.perform(delete(ROLES + "/" + ROLE_ID).with(rbacManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(roleService).delete(ROLE_ID);
    }

    // ---------- Web 入口校验 ----------

    @Test
    void invalidPageParametersAreRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(get(ROLES)
                        .with(rbacManager())
                        .queryParam("pageNo", "0")
                        .queryParam("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(roleService, never()).page(any());
    }

    @Test
    void invalidRoleCodeIsRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(post(ROLES)
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "auditor",
                                  "name": "审计员"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(roleService, never()).create(any());
    }

    @Test
    void invalidPermissionIdListOnCreationIsRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(post(ROLES)
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "AUDITOR",
                                  "name": "审计员",
                                  "permissionIds": [0]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(roleService, never()).create(any());
    }

    @Test
    void rbacManagerCanCreateRoleWithoutPermissions() throws Exception {
        when(roleService.create(any())).thenReturn(new RoleResult(
                ROLE_ID, "AUDITOR", "审计员", null, CREATED_AT, List.of()));

        mockMvc.perform(post(ROLES)
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "AUDITOR",
                                  "name": "审计员"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.permissionIds").isEmpty());

        ArgumentCaptor<CreateRoleCommand> requestCaptor =
                ArgumentCaptor.forClass(CreateRoleCommand.class);
        verify(roleService).create(requestCaptor.capture());
        assertThat(requestCaptor.getValue().permissionIds()).isNull();
    }

    private static RoleResult role() {
        return new RoleResult(
                ROLE_ID,
                "AUDITOR",
                "审计员",
                "只读审计角色",
                CREATED_AT,
                List.of(10L, 20L));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor
    rbacManager() {
        return user("rbac-admin")
                .authorities(new SimpleGrantedAuthority("RBAC_MANAGE"));
    }

    private static String createBody() {
        return """
                {
                  "code": "AUDITOR",
                  "name": "审计员",
                  "description": "只读审计角色",
                  "permissionIds": [10, 20]
                }
                """;
    }

    private static String updateBody() {
        return """
                {
                  "name": "高级审计员",
                  "description": null
                }
                """;
    }
}
