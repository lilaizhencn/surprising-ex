package com.surprising.trading.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.Objects;

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
        productLine = Objects.requireNonNull(productLine, "productLine");
        marginMode = MarginMode.defaultIfNull(marginMode);
    }
}
