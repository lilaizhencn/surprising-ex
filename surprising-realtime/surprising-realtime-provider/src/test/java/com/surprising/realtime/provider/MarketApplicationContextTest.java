package com.surprising.realtime.provider;

import com.surprising.candlestick.provider.service.SymbolRegistryService;
import com.surprising.candlestick.provider.service.CandleQueryService;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.RealtimeJsonPublisher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MarketApplicationContextTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void candlesRemainAvailableWithRouterDisabled(ProductLine line) {
        context(line, false).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(CandleQueryService.class)
                    .doesNotHaveBean(RealtimeRouter.class).doesNotHaveBean(RealtimeJsonPublisher.class);
            MockMvcBuilders.webAppContextSetup(ctx).build()
                    .perform(get("/api/v1/candlestick/candles").param("symbol", "BTC-USDT")
                            .param("period", "1m").param("startTime", "2026-09-20T00:00:00Z")
                            .param("endTime", "2026-09-20T01:00:00Z"))
                    .andExpect(status().isOk());
        });
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void enabledRouterSharesProcessWithoutLoopbackPublisher(ProductLine line) {
        context(line, true).withBean("realtimeRouter", RealtimeRouter.class, () -> mock(RealtimeRouter.class))
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(CandleQueryService.class)
                        .hasSingleBean(RealtimeRouter.class).doesNotHaveBean(RealtimeJsonPublisher.class));
    }

    private WebApplicationContextRunner context(ProductLine line, boolean routing) {
        return new WebApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(RealtimeApplication.class)
                .withPropertyValues("surprising.candlestick.kafka.product-line=" + line,
                        "surprising.realtime.router.enabled=" + routing,
                        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
                .withBean("symbolRegistryService", SymbolRegistryService.class, () -> mock(SymbolRegistryService.class))
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean("disableExternalConsumers", BeanPostProcessor.class, () -> new BeanPostProcessor() {
                    @Override public Object postProcessBeforeInitialization(Object bean, String name) {
                        if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) factory.setAutoStartup(false);
                        if (bean instanceof StreamsBuilderFactoryBean factory) factory.setAutoStartup(false);
                        return bean;
                    }
                });
    }
}
