package com.flowdesk.auth;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.config.AuthProperties;
import com.flowdesk.auth.config.JwtProperties;
import com.flowdesk.auth.domain.AuthPrincipal;
import com.flowdesk.auth.domain.AuthSession;
import com.flowdesk.auth.domain.RefreshTokenLookup;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.auth.security.JwtTokenService;
import com.flowdesk.auth.security.RefreshTokenUtils;
import com.flowdesk.common.web.R;
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
import com.flowdesk.support.MockedPersistenceConfiguration;
import com.flowdesk.support.MybatisPlusTestMetadata;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证接口的行为验证：探针路径位于 {@code /fd/v1/auth/**} 之下由 auth 链处理，
 * 会话仓储用替身、IAM 持久化用 {@link MockedPersistenceConfiguration} 的 Mapper 替身，因此不依赖 Redis 与 MySQL。
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
@Import({AuthWebTest.ProbeConfiguration.class, MockedPersistenceConfiguration.class})
class AuthWebTest {

    private static final String LOGIN = "/fd/v1/auth/login";
    private static final String ME = "/fd/v1/auth/me";
    private static final String REFRESH = "/fd/v1/auth/refresh";
    private static final String LOGOUT = "/fd/v1/auth/logout";
    private static final String CHANGE_PASSWORD = "/fd/v1/auth/change-password";
    private static final String PROBE = "/fd/v1/auth/test-probe/who-am-i";
    private static final String PROBE_ADMIN = "/fd/v1/auth/test-probe/admin-only";
    private static final String FOREIGN_ORIGIN = "http://evil.example";

    private static final String SESSION_ID = "3f1c9b7a-0d2e-4c85-9a6f-test-session";
    private static final long USER_ID = 42L;
    private static final String USERNAME = "employee";
    private static final String DISPLAY_NAME = "演示员工";
    private static final List<String> PERMISSIONS = List.of("TICKET_CREATE", "TICKET_VIEW_OWN");
    private static final String RAW_REFRESH_TOKEN = "raw-refresh-token-for-test";
    private static final String CURRENT_PASSWORD = "123456";
    private static final String NEW_PASSWORD = "Updated#FlowDesk2026";
    private static final long ROLE_ID = 10L;
    private static final long PERMISSION_ID = 100L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenService jwtTokenService;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private AuthProperties authProperties;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 本上下文排除了 MyBatis-Plus，IAM 持久化以替身提供。 */
    @Autowired
    private IamUserMapper iamUserMapper;

    @Autowired
    private IamUserRoleMapper iamUserRoleMapper;

    @Autowired
    private IamRoleMapper iamRoleMapper;

    @Autowired
    private IamRolePermissionMapper iamRolePermissionMapper;

    @Autowired
    private IamPermissionMapper iamPermissionMapper;

    /** 本上下文排除了 Redis 自动配置，会话仓储以替身提供。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    /**
     * 真实服务在构建 {@code LambdaQueryWrapper} 时会立即解析列名，测试里没有 MyBatis 上下文，
     * 必须先注册实体元数据，否则包装器构建阶段就会抛 {@code MybatisPlusException}。
     */
    @BeforeAll
    static void registerEntityMetadata() {
        MybatisPlusTestMetadata.initialize(IamUser.class, IamUserRole.class, IamRolePermission.class);
    }

    /**
     * Mapper 替身由 {@link MockedPersistenceConfiguration} 提供，不是 {@code @MockitoBean}，
     * 因此不会在测试之间自动清理：重置它们以免上一个用例的调用记录与桩污染当前用例。
     */
    @BeforeEach
    void resetIdentityPersistenceMock() {
        reset(iamUserMapper, iamUserRoleMapper, iamRoleMapper,
                iamRolePermissionMapper, iamPermissionMapper);
    }

    // ---------- 当前身份 / 请求认证 ----------

    @Test
    void meWithoutTokenIsRejectedAsAuthRequired() throws Exception {
        mockMvc.perform(get(ME))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void meWithUnverifiableTokenIsRejectedAsAuthRequired() throws Exception {
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer("not-a-jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void meWithExpiredTokenIsRejectedAsAuthRequired() throws Exception {
        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(expiredToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void meWithMissingSessionIsRejectedAsSessionInvalid() throws Exception {
        when(authSessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_INVALID"));
    }

    @Test
    void meWithExpiredSessionIsRejectedAsSessionInvalid() throws Exception {
        when(authSessionRepository.findById(SESSION_ID))
                .thenReturn(Optional.of(session(Instant.now().minusSeconds(1))));

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_INVALID"));
    }

    @Test
    void meReturnsIdentityAndSessionPermissions() throws Exception {
        stubActiveSession();

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(USER_ID))
                .andExpect(jsonPath("$.data.username").value(USERNAME))
                .andExpect(jsonPath("$.data.displayName").value(DISPLAY_NAME))
                .andExpect(jsonPath("$.data.roles", contains("EMPLOYEE")))
                .andExpect(jsonPath("$.data.permissions", contains("TICKET_CREATE", "TICKET_VIEW_OWN")));
    }

    /**
     * 会话快照里不再有显示名称：改名后无需重新登录即可在 {@code /auth/me} 上看到最新值。
     */
    @Test
    void meReadsDisplayNameFromLiveProfileInsteadOfSessionSnapshot() throws Exception {
        when(authSessionRepository.findById(SESSION_ID))
                .thenReturn(Optional.of(session(Instant.now().plus(Duration.ofDays(7)))));
        stubProfile(IamUserStatus.ENABLED, "改名后的员工");

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("改名后的员工"))
                .andExpect(jsonPath("$.data.roles", contains("EMPLOYEE")));
    }

    /** 用户已被删除时，会话必须一并失效，不能只返回一个缺了资料的 200。 */
    @Test
    void meWithMissingProfileIsRejectedAsSessionInvalidAndRevokesEverySession() throws Exception {
        when(authSessionRepository.findById(SESSION_ID))
                .thenReturn(Optional.of(session(Instant.now().plus(Duration.ofDays(7)))));
        when(iamUserMapper.selectProfileById(USER_ID)).thenReturn(null);

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_INVALID"));

        verify(authSessionRepository).revokeAll(USER_ID);
    }

    /** 账号被停用后，未过期的 Access Token 也不能继续换取身份。 */
    @Test
    void meWithDisabledProfileIsRejectedAsSessionInvalidAndRevokesEverySession() throws Exception {
        when(authSessionRepository.findById(SESSION_ID))
                .thenReturn(Optional.of(session(Instant.now().plus(Duration.ofDays(7)))));
        stubProfile(IamUserStatus.DISABLED, DISPLAY_NAME);

        mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, bearer(accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_INVALID"));

        verify(authSessionRepository).revokeAll(USER_ID);
    }

    @Test
    void sessionPermissionsReachMethodSecurity() throws Exception {
        stubActiveSession();

        mockMvc.perform(get(PROBE).header(HttpHeaders.AUTHORIZATION, bearer(accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(USER_ID))
                .andExpect(jsonPath("$.data.authorities", contains("TICKET_CREATE", "TICKET_VIEW_OWN")));
    }

    @Test
    void missingPermissionIsRejectedAsAccessDenied() throws Exception {
        stubActiveSession();

        mockMvc.perform(get(PROBE_ADMIN).header(HttpHeaders.AUTHORIZATION, bearer(accessToken())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // ---------- 登录 ----------

    @Test
    void loginReturnsAccessTokenAndKeepsRefreshTokenInCookieOnly() throws Exception {
        stubLoginIdentity(IamUserStatus.ENABLED);

        MvcResult result = mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USERNAME, CURRENT_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn")
                        .value(jwtProperties.expiration().toSeconds()))
                .andExpect(jsonPath("$.data.user.id").value(USER_ID))
                .andExpect(jsonPath("$.data.user.username").value(USERNAME))
                .andExpect(jsonPath("$.data.user.displayName").value(DISPLAY_NAME))
                .andExpect(jsonPath("$.data.user.roles", contains("EMPLOYEE")))
                .andExpect(jsonPath("$.data.user.permissions", contains("TICKET_CREATE")))
                .andReturn();

        String issuedCookie = result.getResponse().getCookie(cookieName()).getValue();
        // Refresh Token 只允许出现在 Cookie 里；响应体也不得回显口令或密码摘要
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(issuedCookie, "password", CURRENT_PASSWORD);

        ArgumentCaptor<AuthSession> saved = ArgumentCaptor.forClass(AuthSession.class);
        verify(authSessionRepository).save(saved.capture());
        assertThat(saved.getValue().userId()).isEqualTo(USER_ID);
        assertThat(saved.getValue().username()).isEqualTo(USERNAME);
        assertThat(saved.getValue().refreshDigest())
                .isEqualTo(RefreshTokenUtils.digest(issuedCookie));
        assertThat(saved.getValue().roleCodes()).containsExactly("EMPLOYEE");
        assertThat(saved.getValue().permissionCodes()).containsExactly("TICKET_CREATE");
        assertThat(saved.getValue().expiresAt()).isAfter(saved.getValue().createdAt());
    }

    /** 用户不存在、账号停用与密码错误共用同一出口，且都不能留下半成品会话。 */
    @Test
    void loginWithUnknownUsernameIsRejectedWithoutCreatingSession() throws Exception {
        when(iamUserMapper.selectOne(any())).thenReturn(null);

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody("nobody", CURRENT_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));

        verify(authSessionRepository, never()).save(any());
    }

    @Test
    void loginWithDisabledUserIsRejectedWithoutCreatingSession() throws Exception {
        stubLoginIdentity(IamUserStatus.DISABLED);

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USERNAME, CURRENT_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));

        verify(authSessionRepository, never()).save(any());
    }

    @Test
    void loginWithWrongPasswordIsRejectedWithoutCreatingSession() throws Exception {
        stubLoginIdentity(IamUserStatus.ENABLED);

        mockMvc.perform(post(LOGIN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(USERNAME, "WrongPassword#2026")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));

        verify(authSessionRepository, never()).save(any());
    }

    // ---------- 刷新 ----------

    @Test
    void refreshRotatesTokenAndReturnsNewAccessToken() throws Exception {
        when(authSessionRepository.findByRefreshDigest(RefreshTokenUtils.digest(RAW_REFRESH_TOKEN)))
                .thenReturn(RefreshTokenLookup.active(SESSION_ID));
        stubActiveSession();

        MvcResult result = mockMvc.perform(post(REFRESH)
                        .cookie(refreshCookie(RAW_REFRESH_TOKEN))
                        .header(HttpHeaders.ORIGIN, allowedOrigin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").exists())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andReturn();

        String issuedCookie = result.getResponse().getCookie(cookieName()).getValue();
        ArgumentCaptor<AuthSession> rotated = ArgumentCaptor.forClass(AuthSession.class);
        verify(authSessionRepository).rotate(rotated.capture(), eq(RefreshTokenUtils.digest(RAW_REFRESH_TOKEN)));

        // 写回 Cookie 的必须是新令牌，否则下一次刷新会被判成重放
        assertThat(RefreshTokenUtils.digest(issuedCookie)).isEqualTo(rotated.getValue().refreshDigest());
        // Refresh Token 只允许出现在 Cookie 里，不能出现在响应体
        assertThat(result.getResponse().getContentAsString()).doesNotContain(issuedCookie, "password");
    }

    @Test
    void refreshWithoutCookieIsRejectedAsSessionInvalid() throws Exception {
        mockMvc.perform(post(REFRESH).header(HttpHeaders.ORIGIN, allowedOrigin()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_INVALID"));
    }

    @Test
    void refreshWithUnknownDigestIsRejectedAsSessionInvalid() throws Exception {
        when(authSessionRepository.findByRefreshDigest(any())).thenReturn(RefreshTokenLookup.unknown());

        mockMvc.perform(post(REFRESH).cookie(refreshCookie("never-issued"))
                        .header(HttpHeaders.ORIGIN, allowedOrigin()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_INVALID"));
    }

    @Test
    void replayedTokenRevokesTheWholeSession() throws Exception {
        when(authSessionRepository.findByRefreshDigest(any())).thenReturn(RefreshTokenLookup.reused(SESSION_ID));

        mockMvc.perform(post(REFRESH).cookie(refreshCookie("already-rotated"))
                        .header(HttpHeaders.ORIGIN, allowedOrigin()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_SESSION_INVALID"));

        verify(authSessionRepository).revoke(SESSION_ID);
        verify(authSessionRepository, never()).rotate(any(), any());
    }

    @Test
    void refreshWithForeignOriginIsRejected() throws Exception {
        mockMvc.perform(post(REFRESH).cookie(refreshCookie(RAW_REFRESH_TOKEN))
                        .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));

        verify(authSessionRepository, never()).rotate(any(), any());
    }

    @Test
    void refreshWithMissingOriginIsRejected() throws Exception {
        mockMvc.perform(post(REFRESH).cookie(refreshCookie(RAW_REFRESH_TOKEN)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));
    }

    // ---------- 退出 ----------

    @Test
    void logoutRevokesSessionAndClearsCookie() throws Exception {
        when(authSessionRepository.findByRefreshDigest(RefreshTokenUtils.digest(RAW_REFRESH_TOKEN)))
                .thenReturn(RefreshTokenLookup.active(SESSION_ID));

        MvcResult result = mockMvc.perform(post(LOGOUT)
                        .cookie(refreshCookie(RAW_REFRESH_TOKEN))
                        .header(HttpHeaders.ORIGIN, allowedOrigin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();

        verify(authSessionRepository).revoke(SESSION_ID);
        assertThat(result.getResponse().getCookie(cookieName()).getMaxAge()).isZero();
    }

    /** 幂等：没有 Cookie（或会话已不存在）时同样成功，且不泄露会话状态。 */
    @Test
    void logoutWithoutCookieStillSucceeds() throws Exception {
        MvcResult result = mockMvc.perform(post(LOGOUT).header(HttpHeaders.ORIGIN, allowedOrigin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();

        verify(authSessionRepository, never()).revoke(any());
        assertThat(result.getResponse().getCookie(cookieName()).getMaxAge()).isZero();
    }

    @Test
    void logoutWithForeignOriginIsRejected() throws Exception {
        mockMvc.perform(post(LOGOUT).cookie(refreshCookie(RAW_REFRESH_TOKEN))
                        .header(HttpHeaders.ORIGIN, FOREIGN_ORIGIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORIGIN_NOT_ALLOWED"));
    }

    // ---------- 修改本人密码 ----------

    @Test
    void changePasswordRevokesEverySessionBeforeWritingTheNewPassword() throws Exception {
        stubActiveSession();
        stubUserWithCurrentPassword();

        MvcResult result = mockMvc.perform(post(CHANGE_PASSWORD)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(CURRENT_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andReturn();

        // 顺序是刻意的：先撤 Redis 会话，再写 MySQL 密码
        InOrder order = inOrder(authSessionRepository, iamUserMapper);
        order.verify(authSessionRepository).revokeAll(USER_ID);
        order.verify(iamUserMapper).update(isNull(), any());
        assertThat(result.getResponse().getCookie(cookieName()).getMaxAge()).isZero();
    }

    @Test
    void changePasswordWithWrongCurrentPasswordKeepsSessions() throws Exception {
        stubActiveSession();
        stubUserWithCurrentPassword();

        mockMvc.perform(post(CHANGE_PASSWORD)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody("wrong-current-password", NEW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));

        verify(authSessionRepository, never()).revokeAll(anyLong());
        verify(iamUserMapper, never()).update(isNull(), any());
    }

    @Test
    void changePasswordRejectsTooShortNewPassword() throws Exception {
        stubActiveSession();

        mockMvc.perform(post(CHANGE_PASSWORD)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(CURRENT_PASSWORD, "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[0].field").value("newPassword"));
    }

    @Test
    void changePasswordVersionConflictIsReportedAsUserConflict() throws Exception {
        stubActiveSession();
        stubUserWithCurrentPassword();
        when(iamUserMapper.update(isNull(), any())).thenReturn(0);      // 版本已被其他操作改动

        mockMvc.perform(post(CHANGE_PASSWORD)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(CURRENT_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_CONFLICT"));
    }

    @Test
    void changePasswordWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(post(CHANGE_PASSWORD)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(passwordBody(CURRENT_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    // ---------- 辅助 ----------

    /**
     * 会话快照有效：令牌验签通过且 Redis 里能查到未过期的会话。
     *
     * <p>2026-09-23 重构后会话快照不再保存显示名称，登录与 {@code /auth/me} 的响应都改由
     * IAM 实时读取用户资料，因此"会话有效"还必须同时满足"用户存在且启用"。</p>
     */
    private void stubActiveSession() {
        when(authSessionRepository.findById(SESSION_ID))
                .thenReturn(Optional.of(session(Instant.now().plus(Duration.ofDays(7)))));
        stubProfile(IamUserStatus.ENABLED, DISPLAY_NAME);
    }

    /** 实时用户资料替身：{@code displayName} 与状态由它提供，而不是来自会话快照。 */
    private void stubProfile(IamUserStatus status, String displayName) {
        IamUser profile = new IamUser();
        profile.setId(USER_ID);
        profile.setUsername(USERNAME);
        profile.setDisplayName(displayName);
        profile.setStatus(status);
        when(iamUserMapper.selectProfileById(USER_ID)).thenReturn(profile);
    }

    /** 登录链路替身：用户资料、用户角色、角色权限与权限编码各一层，覆盖真实 {@code IamAuthServiceImpl} 的读取顺序。 */
    private void stubLoginIdentity(IamUserStatus status) {
        IamUser user = new IamUser();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        user.setDisplayName(DISPLAY_NAME);
        user.setPassword(passwordEncoder.encode(CURRENT_PASSWORD));
        user.setStatus(status);
        user.setVersion(0L);
        when(iamUserMapper.selectOne(any())).thenReturn(user);

        IamUserRole grant = new IamUserRole();
        grant.setUserId(USER_ID);
        grant.setRoleId(ROLE_ID);
        when(iamUserRoleMapper.selectList(any())).thenReturn(List.of(grant));

        IamRole role = new IamRole();
        role.setId(ROLE_ID);
        role.setCode("EMPLOYEE");
        role.setName("普通员工");
        when(iamRoleMapper.selectByIds(List.of(ROLE_ID))).thenReturn(List.of(role));

        IamRolePermission rolePermission = new IamRolePermission();
        rolePermission.setRoleId(ROLE_ID);
        rolePermission.setPermissionId(PERMISSION_ID);
        when(iamRolePermissionMapper.selectList(any())).thenReturn(List.of(rolePermission));

        IamPermission permission = new IamPermission();
        permission.setId(PERMISSION_ID);
        permission.setCode("TICKET_CREATE");
        permission.setName("创建工单");
        when(iamPermissionMapper.selectByIds(List.of(PERMISSION_ID)))
                .thenReturn(List.of(permission));

        stubProfile(status, DISPLAY_NAME);
    }

    private void stubUserWithCurrentPassword() {
        IamUser user = new IamUser();
        user.setId(USER_ID);
        user.setUsername(USERNAME);
        user.setDisplayName(DISPLAY_NAME);
        user.setPassword(passwordEncoder.encode(CURRENT_PASSWORD));
        user.setStatus(IamUserStatus.ENABLED);
        user.setVersion(0L);
        when(iamUserMapper.selectOne(any())).thenReturn(user);
        when(iamUserMapper.selectById(USER_ID)).thenReturn(user);
        when(iamUserMapper.update(isNull(), any())).thenReturn(1);
    }

    private String accessToken() {
        return jwtTokenService.issue(USER_ID, SESSION_ID).value();
    }

    /** 用一小时前的时钟签发同密钥令牌，得到已过期的 Access Token。 */
    private String expiredToken() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        return new JwtTokenService(jwtProperties, past).issue(USER_ID, SESSION_ID).value();
    }

    /** 会话快照里的摘要必须等于当前 Refresh Token 的摘要，这是登录与轮换共同维持的不变量。 */
    private static AuthSession session(Instant expiresAt) {
        return new AuthSession(SESSION_ID, USER_ID, USERNAME,
                RefreshTokenUtils.digest(RAW_REFRESH_TOKEN),
                List.of("EMPLOYEE"), PERMISSIONS, Instant.now().minus(Duration.ofHours(1)), expiresAt);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private String cookieName() {
        return authProperties.refreshCookieName();
    }

    private Cookie refreshCookie(String value) {
        return new Cookie(cookieName(), value);
    }

    private String allowedOrigin() {
        return authProperties.allowedOrigins().getFirst();
    }

    private static String passwordBody(String currentPassword, String newPassword) {
        return "{\"currentPassword\":\"" + currentPassword + "\",\"newPassword\":\"" + newPassword + "\"}";
    }

    private static String loginBody(String username, String password) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
    }

    @TestConfiguration(proxyBeanMethods = false)
    @ImportAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            RedisAutoConfiguration.class,
            RedisRepositoriesAutoConfiguration.class
    })
    static class ProbeConfiguration {

        /** 仅存在于测试上下文：用来观察请求认证之后安全上下文里的实际内容。 */
        @RestController
        @RequestMapping("/fd/v1/auth/test-probe")
        static class ProbeController {

            @GetMapping("/who-am-i")
            @PreAuthorize("hasAuthority('TICKET_CREATE')")
            R<WhoAmI> whoAmI() {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
                return R.success(new WhoAmI(principal.userId(), principal.sessionId(),
                        authentication.getAuthorities().stream()
                                .map(GrantedAuthority::getAuthority)
                                .sorted()
                                .toList()));
            }

            @GetMapping("/admin-only")
            @PreAuthorize("hasAuthority('USER_MANAGE')")
            R<Void> adminOnly() {
                return R.success(null);
            }
        }
    }

    record WhoAmI(long userId, String sessionId, List<String> authorities) {
    }
}
