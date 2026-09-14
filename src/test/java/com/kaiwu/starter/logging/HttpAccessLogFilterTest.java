package com.kaiwu.starter.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class HttpAccessLogFilterTest {

    @Test
    void logsSafeSummaryAndNeverLogsPayloadOrCredentialHeaders(CapturedOutput output) throws Exception {
        HttpAccessLogFilter filter = new HttpAccessLogFilter("order-service");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/orders");
        request.setQueryString("token=query-secret");
        request.addHeader("Authorization", "Bearer access-secret");
        request.addHeader("X-Kaiwu-Context", "context-secret");
        request.addHeader(HttpAccessLogFilter.TRACE_HEADER, "trace-safe-1");
        request.setContent("{\"password\":\"body-secret\"}".getBytes());
        request.setAttribute(HttpAccessLogFilter.USER_ID_ATTRIBUTE, "10001");
        request.setAttribute(HttpAccessLogFilter.PROJECT_ID_ATTRIBUTE, "900000000000000001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (currentRequest, currentResponse) -> {
            assertThat(MDC.get("traceId")).isEqualTo("trace-safe-1");
            ((HttpServletResponse) currentResponse).setStatus(201);
        });

        assertThat(response.getHeader(HttpAccessLogFilter.TRACE_HEADER)).isEqualTo("trace-safe-1");
        assertThat(MDC.get("traceId")).isNull();
        assertThat(output)
                .contains(
                        "http_request service=order-service traceId=trace-safe-1 method=POST path=/api/orders",
                        "userId=10001 projectId=900000000000000001 status=201");
        assertThat(output).doesNotContain("query-secret", "access-secret", "context-secret", "body-secret");
    }

    @Test
    void replacesInvalidIncomingTraceId(CapturedOutput output) throws Exception {
        HttpAccessLogFilter filter = new HttpAccessLogFilter("order-service");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.addHeader(HttpAccessLogFilter.TRACE_HEADER, "invalid trace\nforged");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (currentRequest, currentResponse) -> {});

        String generated = response.getHeader(HttpAccessLogFilter.TRACE_HEADER);
        assertThat(generated).matches("[a-f0-9]{32}");
        assertThat(output).contains("traceId=" + generated).doesNotContain("forged");
    }

    @Test
    void recordsServerErrorWhenUnhandledExceptionEscapes(CapturedOutput output) {
        HttpAccessLogFilter filter = new HttpAccessLogFilter("order-service");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(request, response, (currentRequest, currentResponse) -> {
                    throw new ServletException("downstream failure");
                }))
                .isInstanceOf(ServletException.class);

        assertThat(output).contains("path=/api/orders/1", "status=500");
        assertThat(MDC.get("traceId")).isNull();
    }
}
