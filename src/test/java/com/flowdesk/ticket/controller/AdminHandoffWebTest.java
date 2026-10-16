package com.flowdesk.ticket.controller;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.iam.application.result.AdminHandoffAssignmentResult;
import com.flowdesk.iam.application.service.IamUserService;
import com.flowdesk.support.MockedPersistenceConfiguration;
import com.flowdesk.ticket.application.result.TicketAssigneeOptionResult;
import com.flowdesk.ticket.application.service.TicketQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Arrays;
import java.util.List;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理性交接两个读取端点的 Web 契约（{@code docs/api-design.md} 8.3）。
 *
 * <p>两个端点分属不同模块的控制器（用户侧在 IAM、工单侧在工单模块），但共用同一个
 * {@code TICKET_ADMIN_HANDOFF} 权限、同一条"管理员只看最小元数据"的边界，因此放在一起断言：
 * 认证、权限（并证明它<strong>不</strong>等于 {@code USER_MANAGE} 或工单查看权限）、响应信封、
 * 错误码映射，以及响应里<strong>不会</strong>出现标题、描述、记录或附件字段。</p>
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
class AdminHandoffWebTest {

    private static final String ASSIGNMENTS = "/fd/v1/admin/users/42/active-ticket-assignments";
    private static final String CANDIDATES = "/fd/v1/admin/tickets/FD-20261012-000001/handoff-candidates";
    private static final long USER_ID = 42L;
    private static final long NEW_ASSIGNEE_ID = 9L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IamUserService iamUserService;

    @MockitoBean
    private TicketQueryService ticketQueryService;

    /** 排除 Redis 自动配置后，用替身满足认证模块的依赖。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    // ---------- 认证 ----------

    @Test
    void activeTicketAssignmentsRequiresAuthentication() throws Exception {
        mockMvc.perform(get(ASSIGNMENTS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(iamUserService, ticketQueryService);
    }

    @Test
    void handoffCandidatesRequiresAuthentication() throws Exception {
        mockMvc.perform(get(CANDIDATES))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(iamUserService, ticketQueryService);
    }

    // ---------- 权限：交接权限既不是用户管理，也不是工单可见性 ----------

    @Test
    void activeTicketAssignmentsRejectsUserManageWithoutHandoffAuthority() throws Exception {
        mockMvc.perform(get(ASSIGNMENTS).with(handoffUser("USER_MANAGE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(iamUserService);
    }

    @Test
    void handoffCandidatesRejectsTicketViewWithoutHandoffAuthority() throws Exception {
        mockMvc.perform(get(CANDIDATES)
                        .with(handoffUser("TICKET_VIEW_OWN", "TICKET_VIEW_PARTICIPATED")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(ticketQueryService);
    }

    // ---------- 待交接工单：最小元数据 ----------

    @Test
    void activeTicketAssignmentsReturnsOnlyHandoffMetadata() throws Exception {
        when(iamUserService.activeTicketAssignments(USER_ID)).thenReturn(List.of(
                new AdminHandoffAssignmentResult(
                        "FD-20261012-000001", "PROCESSING", USER_ID, "演示 IT 支持人员", 7L)));

        mockMvc.perform(get(ASSIGNMENTS).with(handoffUser("TICKET_ADMIN_HANDOFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].ticketNo").value("FD-20261012-000001"))
                .andExpect(jsonPath("$.data[0].status").value("PROCESSING"))
                .andExpect(jsonPath("$.data[0].assigneeId").value(USER_ID))
                .andExpect(jsonPath("$.data[0].version").value(7))
                // 管理员在交接里看不到正文相关字段
                .andExpect(jsonPath("$.data[0].title").doesNotExist())
                .andExpect(jsonPath("$.data[0].description").doesNotExist())
                .andExpect(jsonPath("$.data[0].records").doesNotExist())
                .andExpect(jsonPath("$.data[0].attachments").doesNotExist());
    }

    /** 没有活动工单返回空数组而不是错误：界面据此走"无需交接"的普通流程。 */
    @Test
    void activeTicketAssignmentsReturnsEmptyArrayWhenNothingToHandOff() throws Exception {
        when(iamUserService.activeTicketAssignments(USER_ID)).thenReturn(List.of());

        mockMvc.perform(get(ASSIGNMENTS).with(handoffUser("TICKET_ADMIN_HANDOFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void activeTicketAssignmentsMapsUnknownUserToNotFound() throws Exception {
        when(iamUserService.activeTicketAssignments(USER_ID))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));

        mockMvc.perform(get(ASSIGNMENTS).with(handoffUser("TICKET_ADMIN_HANDOFF")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // ---------- 接替候选人 ----------

    @Test
    void handoffCandidatesReturnsOptionList() throws Exception {
        when(ticketQueryService.handoffCandidates("FD-20261012-000001"))
                .thenReturn(List.of(new TicketAssigneeOptionResult(
                        NEW_ASSIGNEE_ID, "备用 IT 支持")));

        mockMvc.perform(get(CANDIDATES).with(handoffUser("TICKET_ADMIN_HANDOFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(NEW_ASSIGNEE_ID))
                .andExpect(jsonPath("$.data[0].displayName").value("备用 IT 支持"))
                // 候选人只暴露标识与显示名，不返回用户名或角色
                .andExpect(jsonPath("$.data[0].username").doesNotExist())
                .andExpect(jsonPath("$.data[0].roles").doesNotExist());
    }

    @Test
    void handoffCandidatesReturnsEmptyArrayWhenNobodyIsEligible() throws Exception {
        when(ticketQueryService.handoffCandidates("FD-20261012-000001")).thenReturn(List.of());

        mockMvc.perform(get(CANDIDATES).with(handoffUser("TICKET_ADMIN_HANDOFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    /** 工单已不在需要交接的状态：带上当前版本与状态快照，调用方刷新后决定下一步。 */
    @Test
    void handoffCandidatesConflictCarriesCurrentSnapshot() throws Exception {
        when(ticketQueryService.handoffCandidates("FD-20261012-000001"))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "ADMIN_HANDOFF_CONFLICT",
                        "工单已不在需要交接的状态", 9L, "COMPLETED"));

        mockMvc.perform(get(CANDIDATES).with(handoffUser("TICKET_ADMIN_HANDOFF")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_HANDOFF_CONFLICT"))
                .andExpect(jsonPath("$.data.version").value(9))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void handoffCandidatesMapsUnknownTicketToNotFound() throws Exception {
        when(ticketQueryService.handoffCandidates("FD-20261012-000001"))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "工单不存在"));

        mockMvc.perform(get(CANDIDATES).with(handoffUser("TICKET_ADMIN_HANDOFF")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor
    handoffUser(String... authorities) {
        return user("handoff-admin").authorities(Arrays.stream(authorities)
                .map(SimpleGrantedAuthority::new)
                .toList());
    }
}
