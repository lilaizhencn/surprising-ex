package com.surprising.trading.order.config;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.TraceContext;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrderFeignConfiguration {

    @Bean
    public RequestInterceptor orderTraceRequestInterceptor(TradingOrderProperties properties) {
        return template -> {
            if (!template.headers().containsKey(TraceContext.TRACE_ID_HEADER)) {
                template.header(TraceContext.TRACE_ID_HEADER, TraceContext.currentOrCreate());
            }
            TradingOrderProperties.Kafka kafka = properties == null || properties.getKafka() == null
                    ? new TradingOrderProperties.Kafka()
                    : properties.getKafka();
            if (kafka.isProductTopicsEnabled()) {
                ProductLine productLine = kafka.getProductLine();
                if (productLine != null && !template.headers().containsKey("X-Product-Line")) {
                    template.header("X-Product-Line", productLine.name());
                }
            }
        };
    }
}
