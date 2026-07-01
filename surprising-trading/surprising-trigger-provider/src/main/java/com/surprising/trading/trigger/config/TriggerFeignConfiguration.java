package com.surprising.trading.trigger.config;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TriggerFeignConfiguration {

    @Bean
    public RequestInterceptor triggerTraceRequestInterceptor() {
        return template -> {
            String traceId = TriggerTraceContext.current();
            if (traceId != null && !traceId.isBlank()) {
                template.header("X-Trace-Id", traceId);
            }
        };
    }
}
