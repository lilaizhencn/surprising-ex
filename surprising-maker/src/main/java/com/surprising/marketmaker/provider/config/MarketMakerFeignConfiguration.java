package com.surprising.marketmaker.provider.config;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.TraceContext;
import feign.RequestInterceptor;
import feign.Request;
import java.time.Duration;
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

    @Bean
    public Request.Options marketMakerRequestOptions() {
        return new Request.Options(Duration.ofSeconds(1), Duration.ofSeconds(5), true);
    }
}
