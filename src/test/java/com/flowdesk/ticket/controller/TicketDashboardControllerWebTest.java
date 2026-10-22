package com.flowdesk.ticket.controller;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.support.MockedPersistenceConfiguration;
import com.flowdesk.ticket.application.query.TicketDashboardQuery;
import com.flowdesk.ticket.application.result.TicketDashboardAssigneeCountResult;
import com.flowdesk.ticket.application.result.TicketDashboardCategoryCountResult;
import com.flowdesk.ticket.application.result.TicketDashboardPriorityCountResult;
import com.flowdesk.ticket.application.result.TicketDashboardResult;
import com.flowdesk.ticket.application.result.TicketDashboardStatusCountResult;
import com.flowdesk.ticket.application.result.TicketCategorySummaryResult;
import com.flowdesk.ticket.application.result.TicketUserSummaryResult;
import com.flowdesk.ticket.application.service.TicketDashboardService;
import com.flowdesk.ticket.domain.TicketScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据概览接口的 Web 契约测试（{@code docs/api-design.md} 8.5，{@code GET /fd/v1/dashboard/tickets}）。
 *
 * <p>本类只钉住 HTTP 层：认证、接口级权限、参数绑定与校验、不支持参数的拒绝，以及响应信封形状。
 * 三层授权里的后两层（{@code IT_SUPPORT} 身份与范围查看权限）在服务层，由
 * {@code TicketDashboardServiceImplTest} 覆盖；SQL 聚合由 {@code TicketDashboardServiceIT} 覆盖。</p>
 *
 * <p><b>顺序说明</b>：方法级授权发生在控制器方法调用时，而参数绑定与 {@code @Valid} 校验发生在它之前。
 * 因此"未授权 + 缺少 scope"这一组合返回的是 {@code 400} 而不是 {@code 403}——与既有
 * {@code GET /fd/v1/tickets} 完全同一种行为。它不构成授权泄漏：错误响应里没有任何统计结果。
 * 只缺少权限、参数合法的请求仍然是 {@code 403}。</p>
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
class TicketDashboardControllerWebTest {

    private static final String DASHBOARD = "/fd/v1/dashboard/tickets";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TicketDashboardService ticketDashboardService;

    /** 排除 Redis 自动配置后，用替身满足认证模块的依赖。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    // ---------- 认证与接口级权限 ----------

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get(DASHBOARD).queryParam("scope", "PENDING_QUEUE"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(ticketDashboardService);
    }

    /**
     * {@code DASHBOARD_VIEW} 是接口级闸门：{@code SYSTEM_ADMIN} 的内置权限集里没有它，
     * 纯管理员在这里就被挡住，不会走到服务层。
     */
    @Test
    void forbidsWithoutDashboardViewAuthority() throws Exception {
        mockMvc.perform(get(DASHBOARD)
                        .with(dashboardUser("TICKET_ADMIN_HANDOFF"))
                        .queryParam("scope", "PENDING_QUEUE"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(ticketDashboardService);
    }

    /** 服务层判定 IT 身份或范围权限不足时，异常原样映射成 {@code 403/ACCESS_DENIED}。 */
    @Test
    void propagatesServiceAuthorizationFailure() throws Exception {
        when(ticketDashboardService.overview(any()))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "当前身份无权查看数据概览"));

        mockMvc.perform(get(DASHBOARD)
                        .with(dashboardUser("DASHBOARD_VIEW"))
                        .queryParam("scope", "PENDING_QUEUE"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // ---------- 成功响应 ----------

    @Test
    void returnsTotalAndFourGroups() throws Exception {
        when(ticketDashboardService.overview(any())).thenReturn(sampleDashboard());

        mockMvc.perform(get(DASHBOARD)
                        .with(dashboardUser("DASHBOARD_VIEW"))
                        .queryParam("scope", "PARTICIPATED_BY_ME"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.scope").value("PARTICIPATED_BY_ME"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.byStatus[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.data.byStatus[0].count").value(2))
                .andExpect(jsonPath("$.data.byPriority[0].priority").value("HIGH"))
                .andExpect(jsonPath("$.data.byCategory[0].category.id").value(7))
                .andExpect(jsonPath("$.data.byCategory[0].category.name").value("办公设备"))
                .andExpect(jsonPath("$.data.byCategory[0].count").value(3))
                .andExpect(jsonPath("$.data.byAssignee[0].assignee.displayName").value("李工"))
                .andExpect(jsonPath("$.data.byAssignee[1].count").value(1))
                // 未分配组不带 assignee 键：项目全局 non_null 序列化约定
                .andExpect(jsonPath("$.data.byAssignee[1].assignee").doesNotExist());
    }

    /** 绑定结果原样交给服务层：范围、UTC 时间与分类都不在控制器里二次加工。 */
    @Test
    void passesBoundQueryToService() throws Exception {
        when(ticketDashboardService.overview(any())).thenReturn(sampleDashboard());

        mockMvc.perform(get(DASHBOARD)
                        .with(dashboardUser("DASHBOARD_VIEW"))
                        .queryParam("scope", "ASSIGNED_TO_ME")
                        .queryParam("createdFrom", "2026-10-01T00:00:00Z")
                        .queryParam("createdTo", "2026-10-21T00:00:00Z")
                        .queryParam("categoryId", "7"))
                .andExpect(status().isOk());

        ArgumentCaptor<TicketDashboardQuery> captor = ArgumentCaptor.forClass(TicketDashboardQuery.class);
        verify(ticketDashboardService).overview(captor.capture());
        TicketDashboardQuery query = captor.getValue();
        assertThat(query.getScope()).isEqualTo(TicketScope.ASSIGNED_TO_ME);
        assertThat(query.getCreatedFrom()).isEqualTo(OffsetDateTime.parse("2026-10-01T00:00:00Z"));
        assertThat(query.getCreatedTo()).isEqualTo(OffsetDateTime.parse("2026-10-21T00:00:00Z"));
        assertThat(query.getCategoryId()).isEqualTo(7L);
    }

    // ---------- 参数校验 ----------

    @Test
    void rejectsMissingScope() throws Exception {
        mockMvc.perform(get(DASHBOARD).with(dashboardUser("DASHBOARD_VIEW")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem("scope")));

        verifyNoInteractions(ticketDashboardService);
    }

    /** 三个 IT 范围之外没有概览语义：{@code REQUESTED_BY_ME} 按参数错误拒绝，而不是静默返回空统计。 */
    @Test
    void rejectsRequestedByMeScope() throws Exception {
        mockMvc.perform(get(DASHBOARD)
                        .with(dashboardUser("DASHBOARD_VIEW"))
                        .queryParam("scope", "REQUESTED_BY_ME"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem("supportedScope")));

        verifyNoInteractions(ticketDashboardService);
    }

    @ParameterizedTest(name = "scope={0} 不是已知编码")
    @ValueSource(strings = {"FOO", "pending_queue", ""})
    void rejectsUnknownScopeCode(String scope) throws Exception {
        mockMvc.perform(get(DASHBOARD)
                        .with(dashboardUser("DASHBOARD_VIEW"))
                        .queryParam("scope", scope))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(ticketDashboardService);
    }

    @Test
    void rejectsCreatedRangeThatRunsBackwards() throws Exception {
        mockMvc.perform(get(DASHBOARD)
                        .with(dashboardUser("DASHBOARD_VIEW"))
                        .queryParam("scope", "PENDING_QUEUE")
                        .queryParam("createdFrom", "2026-10-06T00:00:00Z")
                        .queryParam("createdTo", "2026-10-01T00:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem("createdRangeValid")));

        verifyNoInteractions(ticketDashboardService);
    }

    @Test
    void rejectsUnparsableCreatedBound() throws Exception {
        mockMvc.perform(get(DASHBOARD)
                        .with(dashboardUser("DASHBOARD_VIEW"))
                        .queryParam("scope", "PENDING_QUEUE")
                        .queryParam("createdFrom", "2026-10-06"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(ticketDashboardService);
    }

    @ParameterizedTest(name = "categoryId={0} 不是正整数")
    @ValueSource(strings = {"0", "-3"})
    void rejectsNonPositiveCategoryId(String categoryId) throws Exception {
        mockMvc.perform(get(DASHBOARD)
                        .with(dashboardUser("DASHBOARD_VIEW"))
                        .queryParam("scope", "PENDING_QUEUE")
                        .queryParam("categoryId", categoryId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem("categoryId")));

        verifyNoInteractions(ticketDashboardService);
    }

    /**
     * 概览只接受 {@code scope} / {@code createdFrom} / {@code createdTo} / {@code categoryId}。
     *
     * <p>其它参数一律拒绝而不是静默忽略：{@code userId}、{@code assigneeId} 这类参数若被忽略，
     * 调用方会误以为"按他人过滤"生效了；{@code page} / {@code status} 这类参数若被忽略，
     * 界面会以为分页或筛选在起作用。{@code @ModelAttribute} 默认丢弃未知参数，
     * 因此控制器必须显式检查——这是本接口唯一一处自己做的参数收口。</p>
     */
    @ParameterizedTest(name = "拒绝不支持的参数 {0}")
    @ValueSource(strings = {"userId", "assigneeId", "teamId", "page", "size", "status", "sort", "keyword"})
    void rejectsUnsupportedQueryParameters(String parameter) throws Exception {
        MockHttpServletRequestBuilder request = get(DASHBOARD)
                .with(dashboardUser("DASHBOARD_VIEW"))
                .queryParam("scope", "PENDING_QUEUE")
                .queryParam(parameter, "1");

        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(ticketDashboardService);
    }

    // ---------- 辅助 ----------

    private static org.springframework.test.web.servlet.request.RequestPostProcessor dashboardUser(
            String... authorities) {
        return user("dashboard-user").authorities(Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList());
    }

    private static TicketDashboardResult sampleDashboard() {
        return new TicketDashboardResult(
                "PARTICIPATED_BY_ME",
                3,
                List.of(
                        new TicketDashboardStatusCountResult("PROCESSING", 2),
                        new TicketDashboardStatusCountResult("COMPLETED", 1)),
                List.of(
                        new TicketDashboardPriorityCountResult("HIGH", 1),
                        new TicketDashboardPriorityCountResult("MEDIUM", 2)),
                List.of(new TicketDashboardCategoryCountResult(
                        new TicketCategorySummaryResult(7L, "办公设备"), 3)),
                List.of(
                        new TicketDashboardAssigneeCountResult(
                                new TicketUserSummaryResult(12L, "李工"), 2),
                        new TicketDashboardAssigneeCountResult(null, 1)));
    }
}
