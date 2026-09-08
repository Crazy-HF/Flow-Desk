package com.flowdesk.shared.api;

import com.flowdesk.shared.web.filter.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void safeTraceIdIsForwardedAndRemovedFromMdcAfterRequest() throws Exception {
        String traceId = "safe-trace-id-20260907";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER_NAME, traceId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isEqualTo(traceId));

        assertThat(response.getHeader(TraceIdFilter.HEADER_NAME)).isEqualTo(traceId);
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void unsafeTraceIdIsReplacedWithGeneratedUuid() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.HEADER_NAME, "not safe\\r\\nheader");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThatCodeIsUuid(response.getHeader(TraceIdFilter.HEADER_NAME));
    }

    private void assertThatCodeIsUuid(String value) {
        assertThat(UUID.fromString(value)).isNotNull();
    }
}
