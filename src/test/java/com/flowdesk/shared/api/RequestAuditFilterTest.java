package com.flowdesk.shared.api;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.flowdesk.shared.web.filter.RequestAuditFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestAuditFilterTest {

    @Test
    void auditLogDoesNotContainSensitiveHeadersOrRequestBody() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(RequestAuditFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/fd/v1/auth/login");
            request.addHeader("Authorization", "Bearer access-token-must-not-appear");
            request.addHeader("Cookie", "refresh-token-must-not-appear");
            request.setContent("{\"password\":\"password-must-not-appear\"}".getBytes());
            MockHttpServletResponse response = new MockHttpServletResponse();

            new RequestAuditFilter().doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                    ((MockHttpServletResponse) ignoredResponse).setStatus(202));

            String message = appender.list.getLast().getFormattedMessage();
            assertThat(message).contains("method=POST", "path=/fd/v1/auth/login", "status=202");
            assertThat(message).doesNotContain("access-token-must-not-appear", "refresh-token-must-not-appear",
                    "password-must-not-appear", "Authorization", "Cookie");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
