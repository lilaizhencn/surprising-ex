package com.surprising.trading.trigger.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import feign.RequestTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TriggerFeignConfigurationTest {

    @AfterEach
    void clearTraceContext() {
        TriggerTraceContext.clear();
    }

    @Test
    void forwardsTraceId() {
        TriggerProperties properties = new TriggerProperties();
        TriggerTraceContext.set("trace-trigger-feign-1");
        RequestTemplate template = new RequestTemplate();

        new TriggerFeignConfiguration().triggerTraceRequestInterceptor(properties).apply(template);

        assertThat(template.headers())
                .containsEntry("X-Trace-Id", java.util.List.of("trace-trigger-feign-1"));
    }

    @Test
    void forwardsProductLineWhenProductTopicsAreEnabled() {
        TriggerProperties properties = new TriggerProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        RequestTemplate template = new RequestTemplate();

        new TriggerFeignConfiguration().triggerTraceRequestInterceptor(properties).apply(template);

        assertThat(template.headers())
                .containsEntry("X-Product-Line", java.util.List.of("LINEAR_DELIVERY"));
    }

    @Test
    void doesNotForwardProductLineInLegacyTopicMode() {
        TriggerProperties properties = new TriggerProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        RequestTemplate template = new RequestTemplate();

        new TriggerFeignConfiguration().triggerTraceRequestInterceptor(properties).apply(template);

        assertThat(template.headers()).doesNotContainKey("X-Product-Line");
    }
}
