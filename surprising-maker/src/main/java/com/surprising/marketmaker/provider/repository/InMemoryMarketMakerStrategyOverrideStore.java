package com.surprising.marketmaker.provider.repository;

import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition;
import com.surprising.marketmaker.provider.model.StrategyConfigOverride;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

/** Single-process strategy ownership. Restart restores YAML, never administrator edits. */
@Repository
public class InMemoryMarketMakerStrategyOverrideStore implements MarketMakerStrategyOverrideStore {
    private final Map<String, MarketMakerStrategyDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, StrategyConfigOverride> overrides = new LinkedHashMap<>();

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

    @Override public synchronized List<MarketMakerStrategyDefinition> definitions() {
        return List.copyOf(definitions.values());
    }
    @Override public synchronized MarketMakerStrategyDefinition saveDefinition(
            MarketMakerStrategyDefinition d, String admin, String reason) {
        if (admin == null || admin.isBlank() || reason == null || reason.isBlank() || reason.length() > 1000)
            throw new IllegalArgumentException("admin identity and reason (1-1000 characters) are required");
        String key = key(d.productLine(), d.strategyId());
        var old = definitions.get(key);
        if ((old == null ? 0 : old.version()) != d.version())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "做市配置已更新，请重新加载");
        var saved = new MarketMakerStrategyDefinition(d.strategyId(), d.productLine(), d.enabled(), d.accountIds(),
                d.instrumentIds(), d.baseQuantitySteps(), d.marginMode(), d.spreadTicks(), d.levelSpacingTicks(),
                d.maxInventorySteps(), d.maxInventorySkewPpm(), d.orderLevels(), d.initialAnchorPriceTicks(),
                Math.addExact(d.version(), 1));
        definitions.put(key, saved);
        overrides.remove(key);
        return saved;
    }
    @Override public synchronized List<StrategyConfigOverride> findAll() { return List.copyOf(overrides.values()); }
    @Override public synchronized Optional<StrategyConfigOverride> find(ProductLine line, String strategy) {
        return Optional.ofNullable(overrides.get(key(line, strategy)));
    }
    @Override public synchronized StrategyConfigOverride save(StrategyConfigOverride v) {
        String key = key(v.productLine(), v.strategyId());
        var old = overrides.get(key);
        var saved = new StrategyConfigOverride(v.strategyId(), v.productLine(), v.enabled(), v.baseQuantitySteps(),
                v.marginMode(), v.spreadTicks(), v.levelSpacingTicks(), v.maxInventorySteps(), v.maxInventorySkewPpm(),
                v.orderLevels(), v.updatedByAdminUserId(), v.reason(), Instant.now(), old == null ? 1 : old.version() + 1);
        overrides.put(key, saved);
        return saved;
    }
    @Override public synchronized void delete(ProductLine line, String strategy) { overrides.remove(key(line, strategy)); }
    private static String key(ProductLine line, String strategy) {
        return line.name() + ":" + strategy.toLowerCase(Locale.ROOT);
    }
}
