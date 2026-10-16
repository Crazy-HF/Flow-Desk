package com.flowdesk.notification.controller;

import com.flowdesk.FlowDeskApplication;
import com.flowdesk.auth.infrastructure.AuthSessionRepository;
import com.flowdesk.common.exception.ApiException;
import com.flowdesk.common.web.PageResult;
import com.flowdesk.notification.application.query.NotificationQuery;
import com.flowdesk.notification.application.result.NotificationResult;
import com.flowdesk.notification.application.result.NotificationUnreadCountResult;
import com.flowdesk.notification.application.service.NotificationService;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 站内信接口的 Web 契约测试（{@code docs/api-design.md} 第 9 节）。
 *
 * <p>四个端点都不带方法级 {@code @PreAuthorize}，只要求已认证；服务替换为替身，
 * 本类只验证 HTTP 状态、响应信封、请求绑定、校验失败与本人范围这一层契约，
 * 归属判定与幂等规则已由 {@code NotificationServiceImplTest} 覆盖。</p>
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
class NotificationControllerWebTest {

    private static final String NOTIFICATIONS = "/fd/v1/notifications";
    private static final String UNREAD_COUNT = NOTIFICATIONS + "/unread-count";
    private static final long NOTIFICATION_ID = 9L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

    /** 排除 Redis 自动配置后，用替身满足认证模块的依赖。 */
    @MockitoBean
    private AuthSessionRepository authSessionRepository;

    // ---------- 认证 ----------

    @ParameterizedTest(name = "{0} without authentication returns 401")
    @MethodSource("notificationRequests")
    void everyEndpointRequiresAuthentication(
            String endpoint, MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verifyNoInteractions(notificationService);
    }

    static Stream<Arguments> notificationRequests() {
        return Stream.of(
                Arguments.of("list notifications", get(NOTIFICATIONS)),
                Arguments.of("unread count", get(UNREAD_COUNT)),
                Arguments.of("mark one read", readRequest(NOTIFICATION_ID)),
                Arguments.of("mark all read", post(NOTIFICATIONS + "/actions/read-all")));
    }

    // ---------- 列表 ----------

    @Test
    void authenticatedUserCanListOwnNotifications() throws Exception {
        when(notificationService.page(any())).thenReturn(new PageResult<>(
                List.of(
                        new NotificationResult(NOTIFICATION_ID, "SUPPLEMENT_REQUESTED",
                                "工单 FD-20261006-001 需要你补充信息", "请在工单中查看需要补充的内容。",
                                1001L, "FD-20261006-001", null,
                                LocalDateTime.of(2026, 10, 6, 8, 15, 30)),
                        new NotificationResult(8L, "RESOLUTION_SUBMITTED",
                                "工单 FD-20261005-007 已提交解决结果", null,
                                1000L, "FD-20261005-007",
                                LocalDateTime.of(2026, 10, 6, 9, 0, 0),
                                LocalDateTime.of(2026, 10, 5, 9, 0, 0))),
                2, 50, 2, 1));

        mockMvc.perform(get(NOTIFICATIONS).with(authenticatedUser())
                        .queryParam("page", "2")
                        .queryParam("size", "50"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.size").value(50))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.totalPages").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(NOTIFICATION_ID))
                .andExpect(jsonPath("$.data.items[0].category").value("SUPPLEMENT_REQUESTED"))
                .andExpect(jsonPath("$.data.items[0].title").value("工单 FD-20261006-001 需要你补充信息"))
                .andExpect(jsonPath("$.data.items[0].ticketNo").value("FD-20261006-001"))
                // 未读：readAt 为 null，在全局 non_null 序列化策略下字段整体不出现
                .andExpect(jsonPath("$.data.items[0].readAt").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].createdAt").exists())
                // 已读：readAt 出现
                .andExpect(jsonPath("$.data.items[1].readAt").exists())
                // 正文为 null 的那条同样不出现 body 字段
                .andExpect(jsonPath("$.data.items[1].body").doesNotExist());

        ArgumentCaptor<NotificationQuery> captor = ArgumentCaptor.forClass(NotificationQuery.class);
        verify(notificationService).page(captor.capture());
        assertThat(captor.getValue().getPage()).isEqualTo(2);
        assertThat(captor.getValue().getSize()).isEqualTo(50);
    }

    @Test
    void unreadOnlyIsPassedThroughToTheService() throws Exception {
        when(notificationService.page(any())).thenReturn(new PageResult<>(List.of(), 1, 20, 0, 0));

        mockMvc.perform(get(NOTIFICATIONS).with(authenticatedUser())
                        .queryParam("unreadOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        ArgumentCaptor<NotificationQuery> captor = ArgumentCaptor.forClass(NotificationQuery.class);
        verify(notificationService).page(captor.capture());
        assertThat(captor.getValue().getUnreadOnly()).isTrue();
    }

    /** 缺省不传 unreadOnly 时它是 null，等价于返回全部。 */
    @Test
    void missingUnreadOnlyStaysNull() throws Exception {
        when(notificationService.page(any())).thenReturn(new PageResult<>(List.of(), 1, 20, 0, 0));

        mockMvc.perform(get(NOTIFICATIONS).with(authenticatedUser()))
                .andExpect(status().isOk());

        ArgumentCaptor<NotificationQuery> captor = ArgumentCaptor.forClass(NotificationQuery.class);
        verify(notificationService).page(captor.capture());
        assertThat(captor.getValue().getUnreadOnly()).isNull();
    }

    @ParameterizedTest(name = "list rejects {0}")
    @MethodSource("invalidListQueries")
    void listRejectsInvalidPagingAndClientSideSorting(
            String caseName, String parameter, String value) throws Exception {
        mockMvc.perform(get(NOTIFICATIONS).with(authenticatedUser())
                        .queryParam(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.fieldErrors[*].field", hasItem(
                        "page".equals(parameter) ? "pageNo"
                                : "size".equals(parameter) ? "pageSize" : "fixedSortOnly")));

        verifyNoInteractions(notificationService);
    }

    static Stream<Arguments> invalidListQueries() {
        return Stream.of(
                Arguments.of("a page below one", "page", "0"),
                Arguments.of("a size above one hundred", "size", "101"),
                Arguments.of("a client side orderBy", "orderBy", "created_at"));
    }

    // ---------- 未读计数 ----------

    @Test
    void unreadCountReturnsTheBadgeNumber() throws Exception {
        when(notificationService.unreadCount()).thenReturn(new NotificationUnreadCountResult(3L));

        mockMvc.perform(get(UNREAD_COUNT).with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.unreadCount").value(3));

        verify(notificationService).unreadCount();
    }

    // ---------- 标记已读 ----------

    @Test
    void markingOneNotificationReadReturnsEmptySuccessEnvelope() throws Exception {
        mockMvc.perform(readRequest(NOTIFICATION_ID).with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(notificationService).markRead(NOTIFICATION_ID);
    }

    @Test
    void markingForeignOrUnknownNotificationReadIsReportedAsNotFound() throws Exception {
        org.mockito.Mockito.doThrow(new ApiException(
                        HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "站内信不存在"))
                .when(notificationService).markRead(NOTIFICATION_ID);

        mockMvc.perform(readRequest(NOTIFICATION_ID).with(authenticatedUser()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"))
                .andExpect(jsonPath("$.data.traceId").exists());

        verify(notificationService).markRead(NOTIFICATION_ID);
    }

    @Test
    void markingAllNotificationsReadReturnsEmptySuccessEnvelope() throws Exception {
        mockMvc.perform(post(NOTIFICATIONS + "/actions/read-all").with(authenticatedUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(notificationService).markAllRead();
    }

    // ---------- 辅助 ----------

    private static MockHttpServletRequestBuilder readRequest(long notificationId) {
        return post(NOTIFICATIONS + "/" + notificationId + "/read");
    }

    /** 站内信只要求已认证，没有权限码：给一个不带任何权限的登录身份即可。 */
    private static UserRequestPostProcessor authenticatedUser() {
        return user("notification-user");
    }
}
