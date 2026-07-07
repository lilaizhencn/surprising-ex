package com.surprising.marketmaker.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.TraceContext;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MarketMakerFeignConfiguration {

    @Bean
    public RequestInterceptor marketMakerTraceRequestInterceptor() {
        return template -> {
            template.header(TraceContext.TRACE_ID_HEADER, TraceContext.currentOrCreate());
            ProductLine productLine = MarketMakerProductLineContext.current();
            if (productLine != null) {
                template.header("X-Product-Line", productLine.name());
            }
        };
    }
}
