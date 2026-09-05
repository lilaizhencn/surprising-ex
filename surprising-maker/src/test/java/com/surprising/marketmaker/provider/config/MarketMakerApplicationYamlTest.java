package com.surprising.marketmaker.provider.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import jakarta.validation.Validation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class MarketMakerApplicationYamlTest {

    @Test
    void defaultStrategyRunsWithDeepBookAndKnownInternalAccounts() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application", new ClassPathResource("application.yml"));
        MarketMakerProperties properties = bind(Map.of("PRODUCT_LINE", "LINEAR_PERPETUAL"));

        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.market-maker.engine.enabled"))
                .contains(true);
        assertThat(properties.getEngine().getCycleDelayMs()).isEqualTo(100L);
        assertThat(properties.getQuoting().getOrderLevels()).isEqualTo(20);
        assertThat(properties.getQuoting().getMaxOrderOperationsPerCycle()).isEqualTo(40);
        assertThat(properties.getStrategies().getFirst().getAccountIds()).hasSize(2);
        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.market-maker.trade.enabled"))
                .contains(false);
        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.market-maker.quoting.order-reconciliation-interval"))
                .contains("500ms");
        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.market-maker.reference-market.enabled"))
                .contains(false);
        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.market-maker.reference-market.websocket-enabled"))
                .contains(false);
        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.market-maker.reference-market.sources[0].parser"))
                .contains("BINANCE_DEPTH");
        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.market-maker.reference-market.sources[0].websocket-parser"))
                .contains("BINANCE_DEPTH_STREAM");
        assertThat(sources)
                .extracting(source -> source.getProperty("surprising.market-maker.strategies[0].enabled"))
                .contains(true);
    }

    @ParameterizedTest
    @MethodSource("makerMatrixOverrides")
    void makerMatrixOverridesBindToEffectiveValues(Map<String, Object> overrides,
                                                   long cycleDelayMs,
                                                   int orderLevels,
                                                   int maxOperations,
                                                   int accountCount) throws IOException {
        MarketMakerProperties properties = bind(overrides);

        assertThat(properties.getEngine().getCycleDelayMs()).isEqualTo(cycleDelayMs);
        assertThat(properties.getQuoting().getOrderLevels()).isEqualTo(orderLevels);
        assertThat(properties.getQuoting().getMaxOrderOperationsPerCycle()).isEqualTo(maxOperations);
        assertThat(properties.getStrategies().getFirst().getAccountIds()).hasSize(accountCount);
        assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(properties)).isEmpty();
    }

    private static List<Object[]> makerMatrixOverrides() {
        return List.of(
                new Object[]{Map.of("PRODUCT_LINE", "LINEAR_PERPETUAL", "MM_CYCLE_DELAY_MS", "1000", "MM_ORDER_LEVELS", "5",
                        "MM_MAX_ORDER_OPERATIONS_PER_CYCLE", "20", "MM_ACCOUNT_IDS", "900001,900002"),
                        1000L, 5, 20, 2},
                new Object[]{Map.of("PRODUCT_LINE", "LINEAR_PERPETUAL", "MM_CYCLE_DELAY_MS", "50", "MM_ORDER_LEVELS", "50",
                        "MM_MAX_ORDER_OPERATIONS_PER_CYCLE", "160",
                        "MM_ACCOUNT_IDS", "900001,900002,900003,900004,900005,900006,900007,900008"),
                        50L, 50, 160, 8});
    }

    private MarketMakerProperties bind(Map<String, Object> overrides) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("matrix", overrides));
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment).bind("surprising.market-maker", Bindable.of(MarketMakerProperties.class))
                .orElseThrow(() -> new IllegalStateException("market maker properties are required"));
    }
}
