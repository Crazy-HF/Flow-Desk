package com.flowdesk.iam.controller;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.domain.bo.IamPermissionBO;
import com.flowdesk.iam.domain.vo.IamPermissionCreateVO;
import com.flowdesk.iam.domain.vo.IamPermissionQueryVO;
import com.flowdesk.iam.domain.vo.IamPermissionUpdateVO;
import com.flowdesk.iam.service.IamPermissionService;
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
 * 权限管理接口的 Web 契约测试。
 *
 * <p>使用真实安全过滤链与方法级授权，权限服务替换为 mock：本类只验证
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
class IamPermissionControllerWebTest {

    private static final String PERMISSIONS = "/fd/v1/admin/permissions";
    private static final long PERMISSION_ID = 7L;
    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse("2026-09-21T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IamPermissionService permissionService;

    /** 排除 Redis 自动配置后，用替身满足认证模块的依赖。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    // ---------- 认证、鉴权矩阵 ----------

    @ParameterizedTest(name = "{0} without authentication returns 401")
    @MethodSource("permissionRequests")
    void everyEndpointRequiresAuthentication(
            String endpoint, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(permissionService);
    }

    @ParameterizedTest(name = "{0} without RBAC_MANAGE returns 403")
    @MethodSource("permissionRequests")
    void everyEndpointRequiresRbacManage(
            String endpoint, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.with(user("employee")
                        .authorities(new SimpleGrantedAuthority("TICKET_CREATE"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(permissionService);
    }

    static Stream<Arguments> permissionRequests() {
        return Stream.of(
                Arguments.of("list permissions", get(PERMISSIONS)),
                Arguments.of("get permission", get(PERMISSIONS + "/" + PERMISSION_ID)),
                Arguments.of("create permission", post(PERMISSIONS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody())),
                Arguments.of("update permission", put(PERMISSIONS + "/" + PERMISSION_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody())),
                Arguments.of("delete permission", delete(PERMISSIONS + "/" + PERMISSION_ID))
        );
    }

    // ---------- RBAC_MANAGE 成功路径 ----------

    @Test
    void rbacManagerCanListPermissionsAndQueryParametersReachService() throws Exception {
        when(permissionService.page(any())).thenReturn(
                new PageResult<>(List.of(permission()), 2, 10, 1, 1));

        mockMvc.perform(get(PERMISSIONS)
                        .with(rbacManager())
                        .queryParam("pageNo", "2")
                        .queryParam("pageSize", "10")
                        .queryParam("keyword", "ticket")
                        .queryParam("orderBy", "name")
                        .queryParam("orderDirection", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(PERMISSION_ID))
                .andExpect(jsonPath("$.data.items[0].code").value("TICKET_VIEW"))
                .andExpect(jsonPath("$.data.items[0].roleIds[0]").value(10));

        ArgumentCaptor<IamPermissionQueryVO> queryCaptor =
                ArgumentCaptor.forClass(IamPermissionQueryVO.class);
        verify(permissionService).page(queryCaptor.capture());
        IamPermissionQueryVO query = queryCaptor.getValue();
        assertThat(query.getPageNo()).isEqualTo(2);
        assertThat(query.getPageSize()).isEqualTo(10);
        assertThat(query.getKeyword()).isEqualTo("ticket");
        assertThat(query.getOrderBy()).isEqualTo("name");
        assertThat(query.getOrderDirection()).isEqualTo("desc");
    }

    @Test
    void rbacManagerCanGetPermission() throws Exception {
        when(permissionService.getById(PERMISSION_ID)).thenReturn(permission());

        mockMvc.perform(get(PERMISSIONS + "/" + PERMISSION_ID)
                        .with(rbacManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(PERMISSION_ID))
                .andExpect(jsonPath("$.data.code").value("TICKET_VIEW"))
                .andExpect(jsonPath("$.data.name").value("查看工单"));

        verify(permissionService).getById(PERMISSION_ID);
    }

    @Test
    void rbacManagerCanCreatePermissionAndReceives201() throws Exception {
        when(permissionService.create(any())).thenReturn(permission());

        mockMvc.perform(post(PERMISSIONS)
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(PERMISSION_ID))
                .andExpect(jsonPath("$.data.code").value("TICKET_VIEW"));

        ArgumentCaptor<IamPermissionCreateVO> requestCaptor =
                ArgumentCaptor.forClass(IamPermissionCreateVO.class);
        verify(permissionService).create(requestCaptor.capture());
        assertThat(requestCaptor.getValue()).isEqualTo(new IamPermissionCreateVO(
                "TICKET_VIEW", "查看工单", "允许查看工单"));
    }

    @Test
    void rbacManagerCanUpdateOnlyMutablePermissionFields() throws Exception {
        IamPermissionBO updated = new IamPermissionBO(
                PERMISSION_ID,
                "TICKET_VIEW",
                "查看全部工单",
                null,
                CREATED_AT,
                List.of(10L));
        when(permissionService.update(
                org.mockito.ArgumentMatchers.eq(PERMISSION_ID), any()))
                .thenReturn(updated);

        mockMvc.perform(put(PERMISSIONS + "/" + PERMISSION_ID)
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.code").value("TICKET_VIEW"))
                .andExpect(jsonPath("$.data.name").value("查看全部工单"));

        ArgumentCaptor<IamPermissionUpdateVO> requestCaptor =
                ArgumentCaptor.forClass(IamPermissionUpdateVO.class);
        verify(permissionService).update(
                org.mockito.ArgumentMatchers.eq(PERMISSION_ID),
                requestCaptor.capture());
        assertThat(requestCaptor.getValue())
                .isEqualTo(new IamPermissionUpdateVO("查看全部工单", null));
    }

    @Test
    void rbacManagerCanDeletePermission() throws Exception {
        mockMvc.perform(delete(PERMISSIONS + "/" + PERMISSION_ID)
                        .with(rbacManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(permissionService).delete(PERMISSION_ID);
    }

    // ---------- Web 入口校验 ----------

    @Test
    void invalidPageParametersAreRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(get(PERMISSIONS)
                        .with(rbacManager())
                        .queryParam("pageNo", "0")
                        .queryParam("pageSize", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(permissionService, never()).page(any());
    }

    @Test
    void invalidPermissionCodeIsRejectedBeforeServiceInvocation() throws Exception {
        mockMvc.perform(post(PERMISSIONS)
                        .with(rbacManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "code": "ticket_view",
                                  "name": "查看工单"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(permissionService, never()).create(any());
    }

    private static IamPermissionBO permission() {
        return new IamPermissionBO(
                PERMISSION_ID,
                "TICKET_VIEW",
                "查看工单",
                "允许查看工单",
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
                  "code": "TICKET_VIEW",
                  "name": "查看工单",
                  "description": "允许查看工单"
                }
                """;
    }

    private static String updateBody() {
        return """
                {
                  "name": "查看全部工单",
                  "description": null
                }
                """;
    }
}
