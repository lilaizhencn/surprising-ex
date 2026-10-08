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
    void businessSettingsComeFromStoreAndYamlCannotReplaceAdministratorChanges() {
        var infrastructure = new MarketMakerInfrastructureProperties();
        infrastructure.setProductLine(com.surprising.product.api.ProductLine.LINEAR_PERPETUAL);
        var store = org.mockito.Mockito.mock(com.surprising.marketmaker.provider.repository.MarketMakerBusinessSettingsStore.class);
        var settings = MarketMakerBusinessSettings.initialDisabled();
        settings.quoting().setOrderLevels(7);
        org.mockito.Mockito.when(store.load(infrastructure.getProductLine())).thenReturn(
                new com.surprising.marketmaker.provider.repository.MarketMakerBusinessSettingsStore.Settings(
                        settings, 12, "1", "administrator settings", java.time.Instant.now()));
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(MarketMakerConfiguration.class)
                .withBean(MarketMakerInfrastructureProperties.class, () -> infrastructure)
                .withBean(com.surprising.marketmaker.provider.repository.MarketMakerBusinessSettingsStore.class, () -> store)
                .withPropertyValues("surprising.market-maker.engine.enabled=true",
                        "surprising.market-maker.quoting.order-levels=42",
                        "surprising.market-maker.strategies[0].strategy-id=yaml-must-not-apply")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var properties = context.getBean(MarketMakerProperties.class);
                    assertThat(properties.getQuoting().getOrderLevels()).isEqualTo(7);
                    assertThat(properties.getEngine().isEnabled()).isFalse();
                    assertThat(properties.getStrategies()).isEmpty();
                    org.mockito.Mockito.verify(store).initializeDisabled(infrastructure.getProductLine());
                });
    }
}
