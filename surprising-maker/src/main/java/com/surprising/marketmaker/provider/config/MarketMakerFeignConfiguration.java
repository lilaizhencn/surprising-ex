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
            String traceId = TraceContext.current();
            template.removeHeader(TraceContext.TRACE_ID_HEADER);
            template.header(TraceContext.TRACE_ID_HEADER, traceId == null ? TraceContext.newTraceId() : traceId);
            ProductLine productLine = MarketMakerProductLineContext.current();
            if (productLine != null) {
                template.header("X-Product-Line", productLine.name());
            }
        };
    }

    @Bean(destroyMethod = "close")
    public org.apache.hc.client5.http.impl.classic.CloseableHttpClient marketMakerHttpClient() {
        var connections = org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(64).setMaxConnPerRoute(32)
                .setDefaultConnectionConfig(org.apache.hc.client5.http.config.ConnectionConfig.custom()
                        .setConnectTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(1))
                        .setSocketTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(5))
                        .setValidateAfterInactivity(org.apache.hc.core5.util.TimeValue.ofSeconds(10)).build())
                .build();
        return org.apache.hc.client5.http.impl.classic.HttpClients.custom()
                .setConnectionManager(connections)
                .setDefaultRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
                        .setConnectionRequestTimeout(org.apache.hc.core5.util.Timeout.ofSeconds(1)).build())
                // A lost response is uncertain: do not automatically repeat a trading command.
                .disableAutomaticRetries()
                .evictExpiredConnections()
                .evictIdleConnections(org.apache.hc.core5.util.TimeValue.ofMinutes(1))
                .build();
    }

    @Bean
    public feign.Client marketMakerFeignClient(
            org.apache.hc.client5.http.impl.classic.CloseableHttpClient marketMakerHttpClient) {
        return new feign.hc5.ApacheHttp5Client(marketMakerHttpClient);
    }

    @Bean
    public Request.Options marketMakerRequestOptions() {
        return new Request.Options(Duration.ofSeconds(1), Duration.ofSeconds(5), true);
    }
}
