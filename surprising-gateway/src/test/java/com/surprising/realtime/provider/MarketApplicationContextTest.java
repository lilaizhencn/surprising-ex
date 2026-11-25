package com.surprising.realtime.provider;

import com.surprising.candlestick.provider.service.CandleQueryService;
import com.surprising.candlestick.provider.service.SymbolRegistryService;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.RealtimeJsonPublisher;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
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
    void routerPipelinesUseABoundedRedisConnectionPool() {
        context(ProductLine.LINEAR_PERPETUAL, false).run(ctx -> {
            var factory = ctx.getBean(org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory.class);
            assertThat(factory.getClientConfiguration())
                    .isInstanceOf(org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration.class);
            var pooling = (org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration)
                    factory.getClientConfiguration();
            assertThat(pooling.getPoolConfig().getMaxTotal()).isEqualTo(4);
        });
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void exportEnabledWithoutRouterDoesNotStartCore(ProductLine line) {
        context(line, false).withPropertyValues("surprising.trade-export.enabled=true")
            .withBean("disableExportWorker", BeanPostProcessor.class, () -> new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (bean instanceof com.surprising.realtime.provider.export.CoreProjectionKafkaConsumer)
                        return mock(com.surprising.realtime.provider.export.CoreProjectionKafkaConsumer.class);
                    return bean;
                }
            }).run(ctx -> {
                assertThat(ctx).hasNotFailed()
                    .hasSingleBean(com.surprising.realtime.provider.export.CoreProjectionKafkaConsumer.class)
                    .doesNotHaveBean(RealtimeRouter.class);
                assertThat(org.springframework.util.ClassUtils.isPresent(
                        "com.surprising.aeron.bootstrap.AeronCoreLifecycle", ctx.getClassLoader())).isFalse();
                assertThat(ctx.getBean(com.surprising.candlestick.provider.config.CandlestickProperties.class)
                    .getProductLine()).isEqualTo(line);
            });
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void candlesRemainAvailableWithRouterDisabled(ProductLine line) {
        context(line, false).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(CandleQueryService.class)
                    .doesNotHaveBean(RealtimeRouter.class).doesNotHaveBean(RealtimeJsonPublisher.class)
                    .doesNotHaveBean(com.surprising.realtime.provider.export.CoreProjectionKafkaConsumer.class)
                    .doesNotHaveBean(com.surprising.realtime.provider.export.service.CommittedOrderProjectionService.class);
            assertThat(org.springframework.util.ClassUtils.isPresent(
                    "com.surprising.aeron.bootstrap.AeronCoreLifecycle", ctx.getClassLoader())).isFalse();
            if (line == ProductLine.OPTION) {
                assertThat(ctx).hasSingleBean(com.surprising.realtime.provider.option.service.OptionQuoteService.class)
                        .hasSingleBean(com.surprising.realtime.provider.option.controller.OptionQuoteController.class)
                        .hasSingleBean(com.surprising.realtime.provider.option.task.OptionQuotePruneTask.class);
            } else {
                assertThat(ctx).doesNotHaveBean(com.surprising.realtime.provider.option.service.OptionQuoteService.class)
                        .doesNotHaveBean(com.surprising.realtime.provider.option.controller.OptionQuoteController.class)
                        .doesNotHaveBean(com.surprising.realtime.provider.option.task.OptionQuotePruneTask.class);
            }
            MockMvcBuilders.webAppContextSetup(ctx).build()
                    .perform(get("/api/v1/candlestick/candles").param("instrumentId", "1")
                            .param("period", "1m").param("startTime", "2026-09-20T00:00:00Z")
                            .param("endTime", "2026-09-20T01:00:00Z"))
                    .andExpect(status().isOk());
        });
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void enabledRouterSharesProcessWithoutLoopbackPublisher(ProductLine line) {
        context(line, true)
                .withBean("appMediaDriver", Object.class, Object::new)
                .withBean("realtimeRouter", RealtimeRouter.class, () -> mock(RealtimeRouter.class))
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(CandleQueryService.class)
                        .hasSingleBean(RealtimeRouter.class).doesNotHaveBean(RealtimeJsonPublisher.class));
    }

    private WebApplicationContextRunner context(ProductLine line, boolean routing) {
        return new WebApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(TestRealtimeAppConfig.class)
                .withPropertyValues("spring.config.name=application",
                        "management.endpoint.health.group.readiness.include=readinessState",
                        "spring.data.redis.lettuce.pool.max-active=4",
                        "surprising.candlestick.kafka.product-line=" + line,
                        "surprising.realtime.router.enabled=" + routing,
                        "surprising.trade-export.enabled=false",
                        "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
                .withBean("symbolRegistryService", SymbolRegistryService.class, () -> mock(SymbolRegistryService.class))
                .withBean(InstrumentSnapshotCache.class, InstrumentSnapshotCache::new)
                .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .withBean(org.springframework.transaction.PlatformTransactionManager.class,
                        () -> mock(org.springframework.transaction.PlatformTransactionManager.class))
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

    @org.springframework.boot.autoconfigure.SpringBootApplication(scanBasePackages = {
            "com.surprising.realtime.provider", "com.surprising.candlestick.provider"
    })
    @org.springframework.boot.context.properties.EnableConfigurationProperties({
            com.surprising.realtime.provider.config.RealtimeRouterProperties.class,
            com.surprising.candlestick.provider.config.CandlestickProperties.class
    })
    @org.springframework.scheduling.annotation.EnableScheduling
    @org.springframework.kafka.annotation.EnableKafka
    static class TestRealtimeAppConfig {
    }
}
