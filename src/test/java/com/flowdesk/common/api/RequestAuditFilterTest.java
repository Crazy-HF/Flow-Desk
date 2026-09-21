package com.flowdesk.common.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowdesk.common.web.filter.RequestAuditFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestAuditFilterTest {

    private static final String AUTHORIZATION = "Bearer access-token-must-not-appear";
    private static final String COOKIE = "refresh-token-must-not-appear";
    private static final String REQUEST_BODY = "{\"password\":\"password-must-not-appear\"}";
    private static final String PATH = "/fd/v1/auth/login";

    @Test
    void auditLogDoesNotContainSensitiveHeadersOrRequestBody() throws Exception {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            MockHttpServletRequest request = sensitiveRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            new RequestAuditFilter().doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                    ((MockHttpServletResponse) ignoredResponse).setStatus(202));

            ILoggingEvent auditEvent = onlyEventWithMessageContaining(appender, "request end");
            assertThat(auditEvent.getFormattedMessage()).contains("method=POST", "path=" + PATH, "status=202");
            assertThat(auditEvent.getFormattedMessage()).doesNotContain(AUTHORIZATION, COOKIE, REQUEST_BODY);
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void startLineIsDebugAndDoesNotContainSensitiveValues() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestAuditFilter.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            // 用例自己把级别调到 DEBUG：开始行的开关本来就是级别，这里要同时验证
            // "级别够时输出"与"级别不够时不输出"两件事的后半段由下一条用例负责。
            logger.setLevel(Level.DEBUG);
            MockHttpServletRequest request = sensitiveRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            new RequestAuditFilter().doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                    ((MockHttpServletResponse) ignoredResponse).setStatus(200));

            ILoggingEvent startEvent = onlyEventWithMessageContaining(appender, RequestAuditFilter.START_MESSAGE);
            // 开始行必须是 DEBUG：生产（root=WARN、com.flowdesk=INFO）不应为每个请求付这份 I/O
            assertThat(startEvent.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(startEvent.getFormattedMessage()).contains("method=POST", "path=" + PATH);
            assertThat(startEvent.getFormattedMessage())
                    .doesNotContain(AUTHORIZATION, COOKIE, REQUEST_BODY, "Authorization", "Cookie");
        } finally {
            logger.setLevel(originalLevel);
            detachAppender(appender);
        }
    }

    @Test
    void startLineIsSilentWhenDebugIsOff() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestAuditFilter.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            logger.setLevel(Level.INFO);
            MockHttpServletRequest request = sensitiveRequest();
            MockHttpServletResponse response = new MockHttpServletResponse();

            new RequestAuditFilter().doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                    ((MockHttpServletResponse) ignoredResponse).setStatus(200));

            // 生产默认就是这个级别：只有一行结束行，不给每个请求增加额外输出
            assertThat(appender.list)
                    .as("DEBUG 关闭时不应出现开始行")
                    .noneMatch(event -> event.getFormattedMessage().contains(RequestAuditFilter.START_MESSAGE));
            assertThat(appender.list)
                    .as("结束行在 INFO 级别仍然输出")
                    .anyMatch(event -> event.getFormattedMessage().contains("request end"));
        } finally {
            logger.setLevel(originalLevel);
            detachAppender(appender);
        }
    }

    /** 每个用例自己挂一个 appender，避免用例之间互相看到对方的日志事件。 */
    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestAuditFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestAuditFilter.class);
        logger.detachAppender(appender);
        appender.stop();
    }

    /**
     * 按内容筛选而不是按顺序取：用例只关心"这一行的内容与级别"，
     * 不该因为日志行顺序或新增一行而失败。
     */
    private ILoggingEvent onlyEventWithMessageContaining(ListAppender<ILoggingEvent> appender, String fragment) {
        List<ILoggingEvent> matches = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains(fragment))
                .toList();
        assertThat(matches)
                .as("期望恰好一条包含 '%s' 的日志", fragment)
                .hasSize(1);
        return matches.getFirst();
    }

    private MockHttpServletRequest sensitiveRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setRequestURI(PATH);
        request.addHeader("Authorization", AUTHORIZATION);
        request.addHeader("Cookie", COOKIE);
        request.setContent(REQUEST_BODY.getBytes());
        return request;
    }
}
