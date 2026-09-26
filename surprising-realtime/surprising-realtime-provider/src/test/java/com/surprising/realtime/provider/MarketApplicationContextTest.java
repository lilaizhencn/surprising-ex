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
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;

    @org.junit.jupiter.api.Test
    void exportRequiresExplicitStorageConfiguration() {
        context(ProductLine.LINEAR_PERPETUAL, false)
                .withPropertyValues("surprising.trade-export.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void exportEnabledWithoutRouterDoesNotStartCore(ProductLine line) {
        context(line, false).withPropertyValues("surprising.trade-export.enabled=true",
                "surprising.trade-export.cluster-directory=" + temp,
                "surprising.trade-export.aeron-directory=" + temp.resolve("driver"),
                "surprising.trade-export.archive-control-channel=aeron:ipc",
                "surprising.trade-export.checkpoint=" + temp.resolve("checkpoint"))
            .withBean("disableExportWorker", BeanPostProcessor.class, () -> new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof com.surprising.realtime.provider.export.TradeExportService)
                        return mock(com.surprising.realtime.provider.export.TradeExportService.class);
                    return bean;
                }
            }).run(ctx -> {
                assertThat(ctx).hasNotFailed()
                    .hasSingleBean(com.surprising.realtime.provider.export.TradeExportService.class)
                    .hasSingleBean(com.surprising.realtime.provider.export.TradeExportProperties.class)
                    .doesNotHaveBean(com.surprising.aeron.service.config.AeronCoreLifecycle.class)
                    .doesNotHaveBean(RealtimeRouter.class);
                assertThat(ctx.getBean(com.surprising.candlestick.provider.config.CandlestickProperties.class)
                    .getKafka().getProductLine()).isEqualTo(line);
            });
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void candlesRemainAvailableWithRouterDisabled(ProductLine line) {
        context(line, false).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(CandleQueryService.class)
                    .doesNotHaveBean(RealtimeRouter.class).doesNotHaveBean(RealtimeJsonPublisher.class)
                    .doesNotHaveBean(com.surprising.realtime.provider.export.TradeExportService.class)
                    .doesNotHaveBean(com.surprising.aeron.service.config.AeronCoreLifecycle.class);
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
                        if (bean instanceof org.springframework.kafka.core.KafkaAdmin admin) admin.setAutoCreate(false);
                        return bean;
                    }
                });
    }
}
