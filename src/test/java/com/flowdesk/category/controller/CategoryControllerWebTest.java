package com.flowdesk.category.controller;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.category.application.result.CategoryOptionResult;
import com.flowdesk.category.application.service.CategoryService;
import com.flowdesk.support.MockedPersistenceConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分类选项接口的 Web 契约测试（员工端 {@code GET /fd/v1/categories/options}）。
 *
 * <p>与 {@link AdminCategoryControllerWebTest} 分开：这个端点服务"新建工单时选分类"，
 * 只要求 {@code TICKET_CREATE} 或 {@code TICKET_PROCESS} 之一，{@code CATEGORY_MANAGE}
 * 不能替代，返回的也必须是只有 {@code id} 与 {@code name} 的最小字段（不泄露停用状态、
 * 排序值或版本）。服务替换为替身，本类只验证 HTTP 契约。</p>
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
class CategoryControllerWebTest {

    private static final String OPTIONS = "/fd/v1/categories/options";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CategoryService categoryService;

    /** 排除 Redis 自动配置后，用替身满足认证模块的依赖。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    @Test
    void optionsWithoutAuthenticationReturns401() throws Exception {
        mockMvc.perform(get(OPTIONS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(categoryService);
    }

    /** 员工建单与 IT 处理工单两条链路都要能读到选项，因此两个权限任一即可。 */
    @ParameterizedTest(name = "options are allowed with {0}")
    @MethodSource("grantedAuthorities")
    void optionsAllowEitherTicketCreateOrTicketProcess(String authority) throws Exception {
        when(categoryService.options()).thenReturn(List.of(
                new CategoryOptionResult(3L, "办公设备"),
                new CategoryOptionResult(7L, "网络与账号")));

        mockMvc.perform(get(OPTIONS).with(authorities(authority)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data[0].id").value(3))
                .andExpect(jsonPath("$.data[0].name").value("办公设备"))
                .andExpect(jsonPath("$.data[1].id").value(7))
                .andExpect(jsonPath("$.data[1].name").value("网络与账号"));

        verify(categoryService).options();
    }

    static Stream<Arguments> grantedAuthorities() {
        return Stream.of(
                Arguments.of("TICKET_CREATE"),
                Arguments.of("TICKET_PROCESS"));
    }

    /** 选项是最小投影：状态、排序值与版本都不出现在响应里。 */
    @Test
    void optionsOnlyExposeIdAndName() throws Exception {
        when(categoryService.options()).thenReturn(List.of(
                new CategoryOptionResult(3L, "办公设备")));

        mockMvc.perform(get(OPTIONS).with(authorities("TICKET_CREATE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").exists())
                .andExpect(jsonPath("$.data[0].name").exists())
                .andExpect(jsonPath("$.data[0].status").doesNotExist())
                .andExpect(jsonPath("$.data[0].sortOrder").doesNotExist())
                .andExpect(jsonPath("$.data[0].version").doesNotExist());
    }

    @ParameterizedTest(name = "options are forbidden with {0}")
    @MethodSource("unrelatedAuthorities")
    void optionsRejectUnrelatedPermissions(String authority) throws Exception {
        mockMvc.perform(get(OPTIONS).with(authorities(authority)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(categoryService);
    }

    /** 分类管理权限管的是业务配置，不等于可以在建单页读到选项。 */
    static Stream<Arguments> unrelatedAuthorities() {
        return Stream.of(
                Arguments.of("CATEGORY_MANAGE"),
                Arguments.of("TICKET_VIEW_OWN"),
                Arguments.of("TICKET_CLAIM"));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor
    authorities(String... authorities) {
        return user("employee").authorities(Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList());
    }
}
