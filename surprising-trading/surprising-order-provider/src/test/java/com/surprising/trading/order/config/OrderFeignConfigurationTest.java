package com.surprising.trading.order.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.TraceContext;
import feign.RequestTemplate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OrderFeignConfigurationTest {

    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    @Test
    void forwardsTraceId() {
        TradingOrderProperties properties = new TradingOrderProperties();
        TraceContext.set("trace-order-feign-1");
        RequestTemplate template = new RequestTemplate();

        new OrderFeignConfiguration().orderTraceRequestInterceptor(properties).apply(template);

        assertThat(template.headers())
                .containsEntry(TraceContext.TRACE_ID_HEADER, List.of("trace-order-feign-1"));
    }

    @Test
    void forwardsProductLineWhenProductTopicsAreEnabled() {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.OPTION);
        RequestTemplate template = new RequestTemplate();

        new OrderFeignConfiguration().orderTraceRequestInterceptor(properties).apply(template);

        assertThat(template.headers())
                .containsEntry("X-Product-Line", List.of("OPTION"));
    }

    @Test
    void doesNotForwardProductLineInLegacyTopicMode() {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        RequestTemplate template = new RequestTemplate();

        new OrderFeignConfiguration().orderTraceRequestInterceptor(properties).apply(template);

        assertThat(template.headers()).doesNotContainKey("X-Product-Line");
    }
}
