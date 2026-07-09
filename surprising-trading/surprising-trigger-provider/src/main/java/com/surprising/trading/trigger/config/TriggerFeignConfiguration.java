package com.surprising.trading.trigger.config;

import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TriggerFeignConfiguration {

    @Bean
    public RequestInterceptor triggerTraceRequestInterceptor(TriggerProperties properties) {
        return template -> {
            String traceId = TriggerTraceContext.current();
            if (traceId != null && !traceId.isBlank() && !template.headers().containsKey("X-Trace-Id")) {
                template.header("X-Trace-Id", traceId);
            }
            TriggerProperties.Kafka kafka = properties == null || properties.getKafka() == null
                    ? new TriggerProperties.Kafka()
                    : properties.getKafka();
            if (kafka.isProductTopicsEnabled() && !template.headers().containsKey("X-Product-Line")) {
                template.header("X-Product-Line", kafka.getProductLine().name());
            }
        };
    }
}
