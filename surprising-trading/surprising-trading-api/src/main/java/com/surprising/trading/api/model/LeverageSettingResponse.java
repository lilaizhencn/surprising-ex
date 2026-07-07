package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

public record LeverageSettingResponse(
        long userId,
        ProductLine productLine,
        String symbol,
        MarginMode marginMode,
        long leveragePpm,
        long maxLeveragePpm,
        long initialMarginRatePpm,
        String source,
        Instant updatedAt) {

    public LeverageSettingResponse {
        productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
        marginMode = MarginMode.defaultIfNull(marginMode);
    }

    public LeverageSettingResponse(long userId,
                                   String symbol,
                                   MarginMode marginMode,
                                   long leveragePpm,
                                   long maxLeveragePpm,
                                   long initialMarginRatePpm,
                                   String source,
                                   Instant updatedAt) {
        this(userId, ProductLine.LINEAR_PERPETUAL, symbol, marginMode, leveragePpm, maxLeveragePpm,
                initialMarginRatePpm, source, updatedAt);
    }
}
