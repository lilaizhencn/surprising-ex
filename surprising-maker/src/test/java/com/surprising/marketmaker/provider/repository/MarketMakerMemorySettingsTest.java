package com.surprising.marketmaker.provider.repository;

import com.surprising.marketmaker.provider.store.InMemoryMarketMakerStrategyOverrideStore;
import com.surprising.marketmaker.provider.store.MarketMakerBusinessSettingsStore;

import static org.assertj.core.api.Assertions.*;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.config.MarketMakerBusinessSettings;
import com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import java.time.Instant;
import com.surprising.marketmaker.provider.controller.AdminMarketMakerController;
import java.util.List;
import org.junit.jupiter.api.*;
import com.surprising.marketmaker.provider.exception.MarketMakerSettingsConflictException;
import tools.jackson.databind.json.JsonMapper;

class MarketMakerMemorySettingsTest {
    @Test void settingsUseYamlResetOnRestartAndRejectStaleOrMutableUpdates() {
        var json = JsonMapper.builder().findAndAddModules().build();
        var yaml = properties();
        yaml.getQuoting().setOrderLevels(7);
        var store = new MarketMakerBusinessSettingsStore(yaml, json);
        var line = ProductLine.LINEAR_PERPETUAL;
        var initial = store.load(line);
        assertThat(initial.settings().quoting().getOrderLevels()).isEqualTo(7);
        initial.settings().quoting().setOrderLevels(9);
        assertThat(store.load(line).settings().quoting().getOrderLevels()).isEqualTo(7);
        assertThat(store.save(line, initial.settings(), 1, "1", "test change").version()).isEqualTo(2);
        initial.settings().quoting().setOrderLevels(12);
        assertThat(store.load(line).settings().quoting().getOrderLevels()).isEqualTo(9);
        assertThatThrownBy(() -> store.save(line, MarketMakerBusinessSettings.initialDisabled(), 1, "2", "stale"))
                .isInstanceOf(MarketMakerSettingsConflictException.class);
        assertThat(store.load(line).settings().quoting().getOrderLevels()).isEqualTo(9);
        assertThat(new MarketMakerBusinessSettingsStore(yaml, json).load(line).settings().quoting().getOrderLevels())
                .isEqualTo(7);
        assertThatThrownBy(() -> store.load(ProductLine.SPOT)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void strategyCreationAndEditingUseVersionAndRestartRestoresYaml() {
        var yaml = properties();
        yaml.setStrategies(List.of(definition(ProductLine.LINEAR_PERPETUAL, 1, 10).strategy()));
        var store = new InMemoryMarketMakerStrategyOverrideStore(yaml);
        assertThat(store.definitions()).singleElement().satisfies(d -> assertThat(d.version()).isEqualTo(1));
        assertThatThrownBy(() -> store.saveDefinition(definition(ProductLine.LINEAR_PERPETUAL, 0, 10)))
                .isInstanceOf(MarketMakerSettingsConflictException.class);
        assertThat(store.saveDefinition(definition(ProductLine.LINEAR_PERPETUAL, 1, 12)).version()).isEqualTo(2);
        assertThatThrownBy(() -> store.saveDefinition(definition(ProductLine.LINEAR_PERPETUAL, 1, 99)))
                .isInstanceOf(MarketMakerSettingsConflictException.class);
        store.saveDefinition(definition(ProductLine.INVERSE_PERPETUAL, 0, 8));
        assertThat(store.definitions()).hasSize(2);
        assertThat(new InMemoryMarketMakerStrategyOverrideStore(yaml).definitions()).singleElement()
                .satisfies(d -> assertThat(d.baseQuantitySteps()).isEqualTo(10));
    }

    @Test void changingStrategyIdCaseCannotReplaceBindingsOrCreateDuplicateWorkers() {
        var yaml = properties();
        var original = definition(ProductLine.LINEAR_PERPETUAL, 1, 10);
        yaml.setStrategies(List.of(original.strategy()));
        var store = new InMemoryMarketMakerStrategyOverrideStore(yaml);
        var renamed = new MarketMakerStrategyDefinition("TEST-MAKER", original.productLine(), true,
                List.of(3L), List.of("653"), 10, MarginMode.CROSS, 10, 10, 1000, 100000, 3, 0, 1);
        assertThatThrownBy(() -> store.saveDefinition(renamed))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ID spelling cannot change");
        assertThat(store.definitions()).containsExactly(original);
    }

    @Test void zeroYamlLevelsUsesConfiguredDefault() {
        var yaml = properties();
        yaml.getQuoting().setOrderLevels(7);
        var strategy = definition(ProductLine.LINEAR_PERPETUAL, 1, 10).strategy();
        strategy.setOrderLevels(0);
        yaml.setStrategies(List.of(strategy));
        assertThat(new InMemoryMarketMakerStrategyOverrideStore(yaml).definitions()).singleElement()
                .satisfies(d -> assertThat(d.orderLevels()).isEqualTo(7));
    }

    @Test void diagnosticEventsAreBoundedAndPaginationKeepsProductsAndEqualTimestampsSeparate() {
        var store = new InMemoryMarketMakerRunEventRepository();
        var time = Instant.parse("2026-10-08T00:00:00Z");
        for (int i = 0; i < 2050; i++) store.record(new MarketMakerRunEventRepository.MarketMakerRunEventWrite(
                "test-maker", i % 2 == 0 ? ProductLine.SPOT : ProductLine.LINEAR_PERPETUAL, "1", 2L, "node", i,
                "CYCLE_SUCCESS", 0, 0, 0, null, "x".repeat(1500), time));
        var page = store.findPage(ProductLine.SPOT, "TEST-MAKER", "1", 2L, "CYCLE_SUCCESS", AdminMarketMakerController.eventPage(1000, null, null));
        assertThat(page.items()).hasSize(1000);
        assertThat(page.items()).allSatisfy(e -> {
            assertThat(e.productLine()).isEqualTo(ProductLine.SPOT);
            assertThat(e.eventId()).isGreaterThan(2);
            assertThat(e.errorMessage()).hasSize(1000);
        });
        var rest = store.findPage(ProductLine.SPOT, null, null, null, null, AdminMarketMakerController.eventPage(1000, page.nextCursor(), null));
        assertThat(rest.items()).hasSize(24);
        assertThat(rest.items()).doesNotContainAnyElementsOf(page.items());
        assertThat(rest.hasMore()).isFalse();
        assertThat(new InMemoryMarketMakerRunEventRepository().findPage(null, null, null, null, null, AdminMarketMakerController.eventPage(100, null, null)).items()).isEmpty();
    }

    private static MarketMakerProperties properties() {
        var p = new MarketMakerProperties(); p.setProductLine(ProductLine.LINEAR_PERPETUAL); return p;
    }
    private MarketMakerStrategyDefinition definition(ProductLine line, long version, long quantity) {
        return new MarketMakerStrategyDefinition("test-maker", line, false, List.of(2L), List.of("604"),
                quantity, MarginMode.CROSS, 10, 10, 1000, 100000, 3, 0, version);
    }
}
