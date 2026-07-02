package com.surprising.marketmaker.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.trading.api.TraceContext;
import feign.RequestTemplate;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class MarketMakerTraceConfigurationTest {

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    @Test
    void filterSetsTraceHeaderAndClearsThreadLocal() throws Exception {
        MarketMakerTraceIdFilter filter = new MarketMakerTraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/market-maker/run-once");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader(TraceContext.TRACE_ID_HEADER, "trace-mm-http-1");
        FilterChain chain = (servletRequest, servletResponse) ->
                assertThat(TraceContext.currentOrCreate()).isEqualTo("trace-mm-http-1");

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(TraceContext.TRACE_ID_HEADER)).isEqualTo("trace-mm-http-1");
        assertThat(TraceContext.currentOrCreate()).isNotEqualTo("trace-mm-http-1");
    }

    @Test
    void feignInterceptorForwardsCurrentTraceId() {
        TraceContext.set("trace-mm-feign-1");
        RequestTemplate template = new RequestTemplate();

        new MarketMakerFeignConfiguration().marketMakerTraceRequestInterceptor().apply(template);

        assertThat(template.headers())
                .containsEntry(TraceContext.TRACE_ID_HEADER, java.util.List.of("trace-mm-feign-1"));
    }
}
