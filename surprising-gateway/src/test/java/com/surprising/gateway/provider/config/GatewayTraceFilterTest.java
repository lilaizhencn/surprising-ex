package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class GatewayTraceFilterTest {

    @Test
    void acceptsValidIncomingTraceId() throws Exception {
        GatewayTraceFilter filter = new GatewayTraceFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/gateway/candlestick");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(GatewayTraceFilter.TRACE_ID_HEADER, "trace-abc.123");

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(request.getAttribute(GatewayTraceFilter.TRACE_ID_ATTRIBUTE)).isEqualTo("trace-abc.123");
        assertThat(response.getHeader(GatewayTraceFilter.TRACE_ID_HEADER)).isEqualTo("trace-abc.123");
    }
}
