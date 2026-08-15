package com.surprising.marketmaker.provider.model;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import java.time.Instant;

public record StrategyConfigOverride(String strategyId,
                                     ProductLine productLine,
                                     Boolean enabled,
                                     Long baseQuantitySteps,
                                     MarginMode marginMode,
                                     Long spreadTicks,
                                     Long levelSpacingTicks,
                                     Long maxInventorySteps,
                                     Long maxInventorySkewPpm,
                                     Integer orderLevels,
                                     String updatedByAdminUserId,
                                     String reason,
                                     Instant updatedAt,
                                     long version) {

    public boolean hasParameterOverride() {
        return enabled != null
                || baseQuantitySteps != null
                || marginMode != null
                || spreadTicks != null
                || levelSpacingTicks != null
                || maxInventorySteps != null
                || maxInventorySkewPpm != null
                || orderLevels != null;
    }
}
