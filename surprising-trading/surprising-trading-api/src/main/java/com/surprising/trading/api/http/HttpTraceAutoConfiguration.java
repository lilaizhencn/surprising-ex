package com.surprising.trading.api.http;

import com.surprising.trading.api.TraceContext;
import feign.RequestInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/** Shared servlet/Feign boundary for all provider applications using the trading contracts. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HttpTraceAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(HttpTraceFilter.class)
    public HttpTraceFilter httpTraceFilter() { return new HttpTraceFilter(); }

    @Bean
    public RequestInterceptor httpTraceRequestInterceptor() {
        return template -> {
            String id = TraceContext.current();
            // A scheduled task owns its lifecycle; don't leave an invented id on a reused thread.
            if (id != null) {
                template.removeHeader(TraceContext.TRACE_ID_HEADER);
                template.header(TraceContext.TRACE_ID_HEADER, id);
            }
        };
    }
}
