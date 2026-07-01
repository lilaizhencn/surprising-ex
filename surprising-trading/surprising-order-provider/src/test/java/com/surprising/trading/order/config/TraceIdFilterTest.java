package com.surprising.trading.order.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.trading.api.TraceContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class TraceIdFilterTest {

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    @Test
    void setsTraceContextForCurrentRequestAndClearsAfterwards() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/orders");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(TraceContext.TRACE_ID_HEADER, "trace-http-1");
        FilterChain chain = (servletRequest, servletResponse) ->
                assertThat(TraceContext.currentOrCreate()).isEqualTo("trace-http-1");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(TraceContext.TRACE_ID_HEADER)).isEqualTo("trace-http-1");
        assertThat(TraceContext.currentOrCreate()).isNotEqualTo("trace-http-1");
    }
}
