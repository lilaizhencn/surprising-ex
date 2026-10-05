package com.surprising.marketmaker.provider.model;

import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import java.util.List;

/** Persistent administrator-owned strategy, including identity and all per-strategy quote parameters. */
public record MarketMakerStrategyDefinition(String strategyId, ProductLine productLine, boolean enabled,
        List<Long> accountIds, List<String> instrumentIds, long baseQuantitySteps, MarginMode marginMode,
        long spreadTicks, long levelSpacingTicks, long maxInventorySteps, long maxInventorySkewPpm,
        int orderLevels, long initialAnchorPriceTicks, long version) {
    public MarketMakerStrategyDefinition {
        if (strategyId == null || !strategyId.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}"))
            throw new IllegalArgumentException("strategyId must contain 1-64 letters, digits, underscores or hyphens");
        if (productLine == null || marginMode == null) throw new IllegalArgumentException("productLine and marginMode are required");
        if (accountIds == null || accountIds.isEmpty() || accountIds.size() > 64
                || accountIds.stream().anyMatch(id -> id == null || id <= 0)
                || accountIds.stream().distinct().count() != accountIds.size())
            throw new IllegalArgumentException("accountIds requires 1-64 unique positive account IDs");
        if (instrumentIds == null || instrumentIds.isEmpty() || instrumentIds.size() > 64
                || instrumentIds.stream().anyMatch(id -> id == null || !id.matches("[1-9][0-9]{0,9}"))
                || instrumentIds.stream().distinct().count() != instrumentIds.size())
            throw new IllegalArgumentException("instrumentIds requires 1-64 unique permanent instrument IDs");
        instrumentIds.forEach(com.surprising.product.api.InstrumentIds::parse);
        accountIds = List.copyOf(accountIds);
        instrumentIds = List.copyOf(instrumentIds);
        if (baseQuantitySteps <= 0 || spreadTicks < 0 || levelSpacingTicks < 0 || maxInventorySteps < 0
                || maxInventorySkewPpm < 0 || maxInventorySkewPpm > 1_000_000 || orderLevels < 1
                || orderLevels > 50 || initialAnchorPriceTicks < 0 || version < 0)
            throw new IllegalArgumentException("invalid maker quantity, spread, inventory, levels or version");
    }

    public MarketMakerProperties.Strategy strategy() {
        var value = new MarketMakerProperties.Strategy();
        value.setStrategyId(strategyId); value.setProductLine(productLine); value.setEnabled(enabled);
        value.setAccountIds(accountIds); value.setInstrumentIds(instrumentIds);
        value.setBaseQuantitySteps(baseQuantitySteps); value.setMarginMode(marginMode);
        value.setSpreadTicks(spreadTicks); value.setLevelSpacingTicks(levelSpacingTicks);
        value.setMaxInventorySteps(maxInventorySteps); value.setMaxInventorySkewPpm(maxInventorySkewPpm);
        value.setOrderLevels(orderLevels); value.setInitialAnchorPriceTicks(initialAnchorPriceTicks);
        return value;
    }
}
