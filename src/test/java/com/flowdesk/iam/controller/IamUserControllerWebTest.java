package com.flowdesk.iam.controller;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.iam.application.command.CreateUserCommand;
import com.flowdesk.iam.application.command.ReplaceUserRolesCommand;
import com.flowdesk.iam.application.command.ResetUserPasswordCommand;
import com.flowdesk.iam.application.command.UpdateUserCommand;
import com.flowdesk.iam.application.command.UserStatusChangeCommand;
import com.flowdesk.iam.application.query.UserQuery;
import com.flowdesk.iam.application.result.UserResult;
import com.flowdesk.iam.application.result.UserRoleSummaryResult;
import com.flowdesk.iam.application.service.IamUserService;
import com.flowdesk.iam.domain.IamUserStatus;
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
import org.springframework.http.HttpStatus;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户管理接口的 Web 契约测试（{@code docs/api-design.md} 8.2）。
 *
 * <p>使用真实安全过滤链与方法级授权，用户服务替换为 mock：本类只验证 HTTP 状态、响应信封、
 * 请求绑定、校验失败与 {@code USER_MANAGE} 权限边界，业务规则由 {@code IamUserServiceImplTest} 覆盖。</p>
 *
 * <p>注意：8.2 表里"替换角色"写的是 {@code PUT /fd/v1/users/{userId}/roles}，当前实现是单数
 * {@code /role}；本类按实现断言，差异已登记待确认，改成复数时需同步改这里。</p>
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
class IamUserControllerWebTest {

    private static final String USERS = "/fd/v1/users";
    private static final long USER_ID = 42L;
    private static final long ROLE_ID = 10L;
    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.parse("2026-09-23T08:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private IamUserService userService;

    /** 排除 Redis 自动配置后，用替身满足认证模块的依赖。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    // ---------- 认证、鉴权矩阵 ----------

    @ParameterizedTest(name = "{0} without authentication returns 401")
    @MethodSource("userRequests")
    void everyEndpointRequiresAuthentication(
            String endpoint, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(userService);
    }

    /** {@code USER_MANAGE} 与 RBAC 管理是两组权限，持有后者不能管理用户。 */
    @ParameterizedTest(name = "{0} without USER_MANAGE returns 403")
    @MethodSource("userRequests")
    void everyEndpointRequiresUserManage(
            String endpoint, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.with(user("rbac-admin")
                        .authorities(new SimpleGrantedAuthority("RBAC_MANAGE"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        verifyNoInteractions(userService);
    }

    static Stream<Arguments> userRequests() {
        return Stream.of(
                Arguments.of("list users", get(USERS)),
                Arguments.of("get user", get(USERS + "/" + USER_ID)),
                Arguments.of("create user", post(USERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody())),
                Arguments.of("update user", put(USERS + "/" + USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody())),
                Arguments.of("enable user", post(USERS + "/" + USER_ID + "/actions/enable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody())),
                Arguments.of("disable user", post(USERS + "/" + USER_ID + "/actions/disable")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody())),
                Arguments.of("replace roles", put(USERS + "/" + USER_ID + "/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceRolesBody())),
                Arguments.of("reset password", post(USERS + "/" + USER_ID + "/actions/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody()))
        );
    }

    // ---------- 分页列表 ----------

    @Test
    void userManagerCanListUsersAndQueryParametersReachService() throws Exception {
        when(userService.page(any())).thenReturn(
                new PageResult<>(List.of(sampleUser()), 2, 10, 1, 1));

        mockMvc.perform(get(USERS)
                        .with(userManager())
                        .queryParam("pageNo", "2")
                        .queryParam("pageSize", "10")
                        .queryParam("keyword", "李")
                        .queryParam("status", "DISABLED")
                        .queryParam("roleId", String.valueOf(ROLE_ID))
                        .queryParam("orderBy", "display_name")
                        .queryParam("orderDirection", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(USER_ID))
                .andExpect(jsonPath("$.data.items[0].username").value("employee"))
                .andExpect(jsonPath("$.data.items[0].displayName").value("演示员工"))
                .andExpect(jsonPath("$.data.items[0].status").value("DISABLED"))
                .andExpect(jsonPath("$.data.items[0].version").value(3))
                .andExpect(jsonPath("$.data.items[0].roles[0].id").value(ROLE_ID))
                .andExpect(jsonPath("$.data.items[0].roles[0].code").value("EMPLOYEE"));

        ArgumentCaptor<UserQuery> queryCaptor = ArgumentCaptor.forClass(UserQuery.class);
        verify(userService).page(queryCaptor.capture());
        UserQuery query = queryCaptor.getValue();
        assertThat(query.getKeyword()).isEqualTo("李");
        assertThat(query.getStatus()).isEqualTo(IamUserStatus.DISABLED);
        assertThat(query.getRoleId()).isEqualTo(ROLE_ID);
        assertThat(query.getPageNo()).isEqualTo(2);
        assertThat(query.getPageSize()).isEqualTo(10);
        assertThat(query.getOrderBy()).isEqualTo("display_name");
        assertThat(query.getOrderDirection()).isEqualTo("desc");
    }

    @Test
    void listRejectsInvalidPagingAndUnknownStatus() throws Exception {
        mockMvc.perform(get(USERS)
                        .with(userManager())
                        .queryParam("pageSize", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(get(USERS)
                        .with(userManager())
                        .queryParam("status", "NOT_A_STATUS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(userService);
    }

    // ---------- 详情 ----------

    @Test
    void userManagerCanReadUserDetail() throws Exception {
        when(userService.getById(USER_ID)).thenReturn(sampleUser());

        mockMvc.perform(get(USERS + "/" + USER_ID).with(userManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(USER_ID))
                .andExpect(jsonPath("$.data.username").value("employee"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-09-23T08:00:00Z"))
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void missingUserIsReportedAsNotFound() throws Exception {
        when(userService.getById(USER_ID))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"));

        mockMvc.perform(get(USERS + "/" + USER_ID).with(userManager()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // ---------- 创建 ----------

    @Test
    void userManagerCanCreateUserAndBodyReachesService() throws Exception {
        when(userService.create(any())).thenReturn(sampleUser());

        mockMvc.perform(post(USERS)
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.username").value("employee"));

        ArgumentCaptor<CreateUserCommand> requestCaptor =
                ArgumentCaptor.forClass(CreateUserCommand.class);
        verify(userService).create(requestCaptor.capture());
        CreateUserCommand request = requestCaptor.getValue();
        assertThat(request.username()).isEqualTo("employee");
        assertThat(request.displayName()).isEqualTo("演示员工");
        assertThat(request.initialPassword()).isEqualTo("Password#2026");
        assertThat(request.roleIds()).containsExactly(ROLE_ID);
    }

    /** 允许零角色：{@code roleIds} 缺省时按"不授予任何角色"处理。 */
    @Test
    void userManagerCanCreateUserWithoutRoles() throws Exception {
        when(userService.create(any())).thenReturn(sampleUser());

        mockMvc.perform(post(USERS)
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "employee",
                                  "displayName": "演示员工",
                                  "initialPassword": "Password#2026"
                                }
                                """))
                .andExpect(status().isCreated());

        ArgumentCaptor<CreateUserCommand> requestCaptor =
                ArgumentCaptor.forClass(CreateUserCommand.class);
        verify(userService).create(requestCaptor.capture());
        assertThat(requestCaptor.getValue().roleIds()).isNull();
    }

    @ParameterizedTest(name = "create rejects {0}")
    @MethodSource("invalidCreateBodies")
    void createRejectsInvalidBody(String caseName, String body) throws Exception {
        mockMvc.perform(post(USERS)
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verify(userService, never()).create(any());
    }

    static Stream<Arguments> invalidCreateBodies() {
        return Stream.of(
                Arguments.of("blank username", """
                        {"username": "  ", "displayName": "演示员工", "initialPassword": "Password#2026"}
                        """),
                Arguments.of("username too long", """
                        {"username": "%s", "displayName": "演示员工", "initialPassword": "Password#2026"}
                        """.formatted("u".repeat(65))),
                Arguments.of("blank display name", """
                        {"username": "employee", "displayName": "", "initialPassword": "Password#2026"}
                        """),
                Arguments.of("password shorter than 8", """
                        {"username": "employee", "displayName": "演示员工", "initialPassword": "1234567"}
                        """),
                Arguments.of("password longer than 64", """
                        {"username": "employee", "displayName": "演示员工", "initialPassword": "%s"}
                        """.formatted("p".repeat(65))),
                Arguments.of("non positive role id", """
                        {"username": "employee", "displayName": "演示员工",
                         "initialPassword": "Password#2026", "roleIds": [0]}
                        """)
        );
    }

    @Test
    void duplicateUsernameIsReportedAsConflict() throws Exception {
        when(userService.create(any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "USERNAME_CONFLICT", "登录名已存在"));

        mockMvc.perform(post(USERS)
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_CONFLICT"));
    }

    @Test
    void unknownRoleDuringCreationIsReportedAsNotFound() throws Exception {
        when(userService.create(any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "角色不存在"));

        mockMvc.perform(post(USERS)
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"));
    }

    // ---------- 修改资料 ----------

    @Test
    void userManagerCanUpdateDisplayNameWithVersion() throws Exception {
        when(userService.update(anyLong(), any())).thenReturn(sampleUser());

        mockMvc.perform(put(USERS + "/" + USER_ID)
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        ArgumentCaptor<UpdateUserCommand> requestCaptor =
                ArgumentCaptor.forClass(UpdateUserCommand.class);
        verify(userService).update(eq(USER_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue().displayName()).isEqualTo("新显示名");
        assertThat(requestCaptor.getValue().version()).isEqualTo(3L);
    }

    @Test
    void updateWithoutVersionIsRejected() throws Exception {
        mockMvc.perform(put(USERS + "/" + USER_ID)
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"displayName": "新显示名"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(userService);
    }

    @Test
    void staleVersionIsReportedAsUserConflict() throws Exception {
        when(userService.update(anyLong(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "USER_CONFLICT", "用户信息已发生变化"));

        mockMvc.perform(put(USERS + "/" + USER_ID)
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_CONFLICT"));
    }

    // ---------- 启用 / 停用 ----------

    @Test
    void userManagerCanEnableUser() throws Exception {
        when(userService.enable(anyLong(), any())).thenReturn(sampleUser());

        mockMvc.perform(post(USERS + "/" + USER_ID + "/actions/enable")
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value(USER_ID));

        ArgumentCaptor<UserStatusChangeCommand> requestCaptor =
                ArgumentCaptor.forClass(UserStatusChangeCommand.class);
        verify(userService).enable(eq(USER_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue().version()).isEqualTo(3L);
    }

    @Test
    void userManagerCanDisableUser() throws Exception {
        when(userService.disable(anyLong(), any())).thenReturn(sampleUser());

        mockMvc.perform(post(USERS + "/" + USER_ID + "/actions/disable")
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        verify(userService).disable(eq(USER_ID), any());
    }

    @Test
    void disablingLastEnabledAdministratorIsReportedAsProtected() throws Exception {
        when(userService.disable(anyLong(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "LAST_ADMIN_PROTECTED",
                        "不能停用最后一个启用管理员"));

        mockMvc.perform(post(USERS + "/" + USER_ID + "/actions/disable")
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(statusBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN_PROTECTED"));
    }

    @Test
    void enableAndDisableRejectMissingVersion() throws Exception {
        mockMvc.perform(post(USERS + "/" + USER_ID + "/actions/enable")
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(post(USERS + "/" + USER_ID + "/actions/disable")
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(userService);
    }

    // ---------- 替换角色 ----------

    @Test
    void userManagerCanReplaceRolesAndBodyReachesService() throws Exception {
        when(userService.replaceRoles(anyLong(), any())).thenReturn(sampleUser());

        mockMvc.perform(put(USERS + "/" + USER_ID + "/role")
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceRolesBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.roles[0].code").value("EMPLOYEE"));

        ArgumentCaptor<ReplaceUserRolesCommand> requestCaptor =
                ArgumentCaptor.forClass(ReplaceUserRolesCommand.class);
        verify(userService).replaceRoles(eq(USER_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue().version()).isEqualTo(3L);
        assertThat(requestCaptor.getValue().roleIds()).containsExactly(ROLE_ID);
    }

    /** 空集合表示该用户最终零角色，是合法请求体而不是校验失败。 */
    @Test
    void replaceRolesAcceptsEmptyRoleSet() throws Exception {
        when(userService.replaceRoles(anyLong(), any())).thenReturn(sampleUser());

        mockMvc.perform(put(USERS + "/" + USER_ID + "/role")
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 3, "roleIds": []}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<ReplaceUserRolesCommand> requestCaptor =
                ArgumentCaptor.forClass(ReplaceUserRolesCommand.class);
        verify(userService).replaceRoles(anyLong(), requestCaptor.capture());
        assertThat(requestCaptor.getValue().roleIds()).isEmpty();
    }

    @Test
    void replaceRolesRejectsMissingVersionAndNonPositiveRoleId() throws Exception {
        mockMvc.perform(put(USERS + "/" + USER_ID + "/role")
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roleIds": [10]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mockMvc.perform(put(USERS + "/" + USER_ID + "/role")
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 3, "roleIds": [10, 0]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(userService);
    }

    @Test
    void replaceRolesProtectsLastEnabledAdministrator() throws Exception {
        when(userService.replaceRoles(anyLong(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "LAST_ADMIN_PROTECTED",
                        "不能移除最后一个启用管理员的管理员角色"));

        mockMvc.perform(put(USERS + "/" + USER_ID + "/role")
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceRolesBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN_PROTECTED"));
    }

    @Test
    void replaceRolesWithUnknownRoleIsReportedAsNotFound() throws Exception {
        when(userService.replaceRoles(anyLong(), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "ROLE_NOT_FOUND", "角色不存在"));

        mockMvc.perform(put(USERS + "/" + USER_ID + "/role")
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replaceRolesBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROLE_NOT_FOUND"));
    }

    // ---------- 重置密码 ----------

    @Test
    void userManagerCanResetPasswordAndResponseCarriesNoData() throws Exception {
        mockMvc.perform(post(USERS + "/" + USER_ID + "/actions/reset-password")
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                // 全局 jackson.default-property-inclusion=non_null：空 data 不进 JSON
                .andExpect(jsonPath("$.data").doesNotExist());

        ArgumentCaptor<ResetUserPasswordCommand> requestCaptor =
                ArgumentCaptor.forClass(ResetUserPasswordCommand.class);
        verify(userService).resetPassword(eq(USER_ID), requestCaptor.capture());
        assertThat(requestCaptor.getValue().version()).isEqualTo(3L);
        assertThat(requestCaptor.getValue().newPassword()).isEqualTo("Reset#FlowDesk2026");
    }

    @Test
    void resetPasswordRejectsTooShortNewPassword() throws Exception {
        mockMvc.perform(post(USERS + "/" + USER_ID + "/actions/reset-password")
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 3, "newPassword": "1234567"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        verifyNoInteractions(userService);
    }

    @Test
    void resetPasswordForMissingUserIsReportedAsNotFound() throws Exception {
        doThrow(new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在"))
                .when(userService).resetPassword(anyLong(), any());

        mockMvc.perform(post(USERS + "/" + USER_ID + "/actions/reset-password")
                        .with(userManager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(resetPasswordBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    // ---------- 辅助 ----------

    private static UserResult sampleUser() {
        return new UserResult(
                USER_ID,
                "employee",
                "演示员工",
                IamUserStatus.DISABLED,
                List.of(new UserRoleSummaryResult(ROLE_ID, "EMPLOYEE", "普通员工")),
                CREATED_AT,
                CREATED_AT,
                3L);
    }

    private static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor
    userManager() {
        return user("user-admin").authorities(new SimpleGrantedAuthority("USER_MANAGE"));
    }

    private static String createBody() {
        return """
                {
                  "username": "employee",
                  "displayName": "演示员工",
                  "initialPassword": "Password#2026",
                  "roleIds": [10]
                }
                """;
    }

    private static String updateBody() {
        return """
                {"displayName": "新显示名", "version": 3}
                """;
    }

    private static String statusBody() {
        return """
                {"version": 3}
                """;
    }

    private static String replaceRolesBody() {
        return """
                {"version": 3, "roleIds": [10]}
                """;
    }

    private static String resetPasswordBody() {
        return """
                {"version": 3, "newPassword": "Reset#FlowDesk2026"}
                """;
    }
}
