package com.surprising.marketmaker.provider.store;

import com.surprising.marketmaker.provider.exception.MarketMakerSettingsConflictException;

import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition;
import com.surprising.marketmaker.provider.model.StrategyConfigOverride;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Single-process strategy ownership. Restart restores YAML, never administrator edits. */
@Component
public class InMemoryMarketMakerStrategyOverrideStore implements MarketMakerStrategyOverrideStore {
    private final Map<String, MarketMakerStrategyDefinition> definitions = new ConcurrentHashMap<>();
    private final Map<String, StrategyConfigOverride> overrides = new ConcurrentHashMap<>();

    public InMemoryMarketMakerStrategyOverrideStore(MarketMakerProperties properties) {
        for (var s : properties.getStrategies()) {
            var definition = new MarketMakerStrategyDefinition(s.getStrategyId(), s.getProductLine(), s.isEnabled(),
                    s.getAccountIds(), s.getInstrumentIds(), s.getBaseQuantitySteps(), s.getMarginMode(),
                    s.getSpreadTicks(), s.getLevelSpacingTicks(),
                    s.getMaxInventorySteps() == null ? properties.getRisk().getMaxInventorySteps() : s.getMaxInventorySteps(),
                    s.getMaxInventorySkewPpm() == null ? properties.getRisk().getMaxInventorySkewPpm() : s.getMaxInventorySkewPpm(),
                    (s.getOrderLevels() == null || s.getOrderLevels() == 0) ? properties.getQuoting().getOrderLevels() : s.getOrderLevels(),
                    s.getInitialAnchorPriceTicks(), 1);
            String key = key(definition.productLine(), definition.strategyId());
            if (definitions.putIfAbsent(key, definition) != null)
                throw new IllegalArgumentException("duplicate maker strategy ID");
        }
    }

    @Override public List<MarketMakerStrategyDefinition> definitions() {
        return List.copyOf(definitions.values());
    }
    @Override public MarketMakerStrategyDefinition saveDefinition(
            MarketMakerStrategyDefinition d) {
        String key = key(d.productLine(), d.strategyId());
        MarketMakerStrategyDefinition saved = definitions.compute(key, (ignored, old) -> {
            // Worker registrations and client order prefixes own the original strategy identity.
            if (old != null && !old.strategyId().equals(d.strategyId()))
                throw new IllegalArgumentException("existing strategy ID spelling cannot change; create a new strategy");
            if ((old == null ? 0 : old.version()) != d.version())
                throw new MarketMakerSettingsConflictException("做市配置已更新，请重新加载");
            return new MarketMakerStrategyDefinition(d.strategyId(), d.productLine(), d.enabled(), d.accountIds(),
                    d.instrumentIds(), d.baseQuantitySteps(), d.marginMode(), d.spreadTicks(), d.levelSpacingTicks(),
                    d.maxInventorySteps(), d.maxInventorySkewPpm(), d.orderLevels(), d.initialAnchorPriceTicks(),
                    Math.addExact(d.version(), 1));
        });
        overrides.remove(key);
        return saved;
    }
    @Override public List<StrategyConfigOverride> findAll() { return List.copyOf(overrides.values()); }
    @Override public Optional<StrategyConfigOverride> find(ProductLine line, String strategy) {
        return Optional.ofNullable(overrides.get(key(line, strategy)));
    }
    @Override public StrategyConfigOverride save(StrategyConfigOverride v) {
        String key = key(v.productLine(), v.strategyId());
        return overrides.compute(key, (ignored, old) ->
                new StrategyConfigOverride(v.strategyId(), v.productLine(), v.enabled(), v.baseQuantitySteps(),
                        v.marginMode(), v.spreadTicks(), v.levelSpacingTicks(), v.maxInventorySteps(), v.maxInventorySkewPpm(),
                        v.orderLevels(), v.updatedByAdminUserId(), v.reason(), Instant.now(), old == null ? 1 : old.version() + 1));
    }
    @Override public void delete(ProductLine line, String strategy) { overrides.remove(key(line, strategy)); }
    private static String key(ProductLine line, String strategy) {
        return line.name() + ":" + strategy.toLowerCase(Locale.ROOT);
    }
}
