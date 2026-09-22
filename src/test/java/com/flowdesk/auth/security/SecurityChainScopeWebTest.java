package com.flowdesk.auth.security;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.domain.AuthSession;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.service.IamRoleService;
import com.flowdesk.support.MockedPersistenceConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全链作用域的回归测试：非 {@code /fd/v1/auth/**} 的业务路径同样必须经过 JWT 请求认证。
 *
 * <p>背景：{@code JwtAuthenticationFilter} 只装在 {@code @Order(1)} 的 auth 链上。auth 链的作用域若被限制为
 * {@code /fd/v1/auth/**}，其余业务路径会落到没有认证过滤器的基础链，被 {@code anyRequest().authenticated()}
 * 一律判成匿名，于是无论带什么令牌都返回 {@code 401/AUTH_REQUIRED}——{@code /fd/v1/admin/**} 就这样长期不可用。</p>
 *
 * <p>本类刻意<b>不使用</b> {@code @WithMockUser}：身份必须由真实 Access Token + 会话快照产生。
 * 测试后置处理器会自己把身份塞进 {@code SecurityContext}，走不到"过滤器是否装在这条链上"这个关键点，
 * 这也正是该缺陷能长期躲过 Web 测试的原因。</p>
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
class SecurityChainScopeWebTest {

    /** 管理端路径：不属于 {@code /fd/v1/auth/**}，正是历史缺陷的现场。 */
    private static final String ADMIN_ROLES = "/fd/v1/admin/roles";

    private static final String SESSION_ID = "9c2b7d10-4f3a-4c1e-88b1-chain-scope-test";
    private static final long USER_ID = 3L;
    private static final String RAW_REFRESH_TOKEN = "raw-refresh-token-for-chain-scope-test";
    private static final List<String> MANAGER_PERMISSIONS = List.of("RBAC_MANAGE", "USER_MANAGE");
    private static final List<String> EMPLOYEE_PERMISSIONS = List.of("TICKET_CREATE", "TICKET_VIEW_OWN");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    /** 本类只验证安全链行为，角色服务用替身，避免把服务层规则混进来。 */
    @MockitoBean
    private IamRoleService roleService;

    /** 排除 Redis 自动配置后，用替身满足认证模块的依赖。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    @Test
    void businessPathWithoutTokenIsRejectedBeforeController() throws Exception {
        mockMvc.perform(get(ADMIN_ROLES))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(roleService);
    }

    /** 修复前这条会返回 {@code 401/AUTH_REQUIRED}：请求没有经过带 JWT 过滤器的链。 */
    @Test
    void businessPathWithValidTokenAndPermissionReachesController() throws Exception {
        stubSession(MANAGER_PERMISSIONS);
        when(roleService.page(any())).thenReturn(new PageResult<>(List.of(), 1, 20, 0, 0));

        mockMvc.perform(get(ADMIN_ROLES).header(HttpHeaders.AUTHORIZATION, bearer(accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.totalElements").value(0));

        verify(roleService).page(any());
    }

    /** 会话里的权限码必须能作用到方法级授权：同一条链、同一个端点，缺权限就是 403 而不是 401。 */
    @Test
    void businessPathWithTokenButWithoutPermissionIsForbidden() throws Exception {
        stubSession(EMPLOYEE_PERMISSIONS);

        mockMvc.perform(get(ADMIN_ROLES).header(HttpHeaders.AUTHORIZATION, bearer(accessToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(roleService);
    }

    @Test
    void businessPathWithRevokedSessionIsRejectedAsSessionInvalid() throws Exception {
        when(authSessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get(ADMIN_ROLES).header(HttpHeaders.AUTHORIZATION, bearer(accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_INVALID"));

        verifyNoInteractions(roleService);
    }

    // ---------- 辅助 ----------

    /** 令牌验签通过，且 Redis 里能查到未过期的会话快照。 */
    private void stubSession(List<String> permissionCodes) {
        when(authSessionRepository.findById(SESSION_ID))
                .thenReturn(Optional.of(new AuthSession(
                        SESSION_ID, USER_ID, "admin", "系统管理员",
                        RefreshTokenUtils.digest(RAW_REFRESH_TOKEN),
                        List.of("SYSTEM_ADMIN"), permissionCodes,
                        Instant.now().minus(Duration.ofHours(1)),
                        Instant.now().plus(Duration.ofDays(7)))));
    }

    private String accessToken() {
        return jwtTokenService.issue(USER_ID, SESSION_ID).value();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
