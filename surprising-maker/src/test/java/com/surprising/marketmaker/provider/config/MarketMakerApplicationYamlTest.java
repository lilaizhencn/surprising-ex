package com.surprising.marketmaker.provider.config;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class MarketMakerApplicationYamlTest {
    @Test
    void priceConsumerDefaultsToOneAndRetainsExplicitConcurrencyOverride() throws Exception {
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        var overrides = new java.util.HashMap<String, Object>();
        overrides.put("PRODUCT_LINE", "LINEAR_PERPETUAL");
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test", overrides));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        var binder = org.springframework.boot.context.properties.bind.Binder.get(env);
        var type = org.springframework.boot.context.properties.bind.Bindable.of(com.surprising.price.consumer.MarkPriceConsumerProperties.class);
        assertThat(binder.bind("surprising.price.consumer", type).get().getConcurrency()).isEqualTo(1);
        overrides.put("PRICE_CONSUMER_CONCURRENCY", "4");
        assertThat(binder.bind("surprising.price.consumer", type).get().getConcurrency()).isEqualTo(4);
    }

    @Test
    void yamlBindsBusinessSettingsAndStrategiesWithoutAnyDataSource() throws Exception {
        var env = new org.springframework.core.env.StandardEnvironment();
        env.getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test", java.util.Map.of(
                "PRODUCT_LINE", "LINEAR_PERPETUAL", "surprising.market-maker.engine.enabled", "true",
                "surprising.market-maker.strategies[0].strategy-id", "test-maker",
                "surprising.market-maker.strategies[0].product-line", "LINEAR_PERPETUAL",
                "surprising.market-maker.strategies[0].account-ids[0]", "2",
                "surprising.market-maker.strategies[0].instrument-ids[0]", "604")));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(env.getPropertySources()::addLast);
        var binder = org.springframework.boot.context.properties.bind.Binder.get(env);
        var infrastructure = binder.bind("surprising.market-maker.infrastructure",
                org.springframework.boot.context.properties.bind.Bindable.of(MarketMakerInfrastructureProperties.class)).get();
        var properties = new MarketMakerConfiguration().marketMakerProperties(infrastructure);
        binder.bind("surprising.market-maker", org.springframework.boot.context.properties.bind.Bindable.ofInstance(properties));
        properties.validateBusinessSettings();
        assertThat(properties.getEngine().isEnabled()).isTrue();
        assertThat(properties.getEngine().getTradeInterval()).isEqualTo(java.time.Duration.ofSeconds(1));
        assertThat(properties.getStrategies()).singleElement().satisfies(s -> assertThat(s.getStrategyId()).isEqualTo("test-maker"));
        assertThat(env.getProperty("spring.datasource.url")).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(com.surprising.product.api.ProductLine.class)
    void processSettingsStartWithoutDatabaseOrRedisBeans(com.surprising.product.api.ProductLine product) {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(MemoryConfiguration.class)
                .withBean(tools.jackson.databind.ObjectMapper.class,
                        () -> tools.jackson.databind.json.JsonMapper.builder().findAndAddModules().build())
                .withPropertyValues("surprising.market-maker.infrastructure.product-line=" + product.name(),
                        "surprising.market-maker.engine.enabled=false",
                        "surprising.market-maker.strategies[0].strategy-id=yaml-maker",
                        "surprising.market-maker.strategies[0].product-line=" + product.name(),
                        "surprising.market-maker.strategies[0].account-ids[0]=2",
                        "surprising.market-maker.strategies[0].instrument-ids[0]=1")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(javax.sql.DataSource.class);
                    assertThat(context.getBean(com.surprising.marketmaker.provider.repository.MarketMakerStrategyOverrideStore.class)
                            .definitions()).singleElement().satisfies(d -> assertThat(d.strategyId()).isEqualTo("yaml-maker"));
                    assertThat(context.getBean(com.surprising.marketmaker.provider.repository.MarketMakerBusinessSettingsStore.class)
                            .load(product).settings().engine().isEnabled()).isFalse();
                });
    }

    @Test
    void runtimeClasspathContainsNoStorageClients() {
        for (String type : java.util.List.of("org.springframework.jdbc.core.JdbcTemplate", "org.postgresql.Driver",
                "org.springframework.data.redis.core.StringRedisTemplate", "io.lettuce.core.RedisClient"))
            assertThat(org.springframework.util.ClassUtils.isPresent(type, getClass().getClassLoader()))
                    .as("maker runtime must not include %s", type).isFalse();
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(MarketMakerInfrastructureProperties.class)
    @org.springframework.context.annotation.Import({MarketMakerConfiguration.class,
            com.surprising.marketmaker.provider.repository.MarketMakerBusinessSettingsStore.class,
            com.surprising.marketmaker.provider.repository.InMemoryMarketMakerStrategyOverrideStore.class,
            com.surprising.marketmaker.provider.repository.InMemoryMarketMakerRunEventRepository.class})
    static class MemoryConfiguration {}
}
