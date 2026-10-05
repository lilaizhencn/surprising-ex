package com.surprising.marketmaker.provider.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Administrator-owned business settings. Transport, credentials and node identity are not included. */
public record MarketMakerBusinessSettings(@NotNull @Valid MarketMakerProperties.Engine engine,
        @NotNull @Valid MarketMakerProperties.Quoting quoting,
        @NotNull @Valid MarketMakerProperties.Risk risk,
        @NotNull @Valid MarketMakerProperties.Trade trade,
        @NotNull @Valid MarketMakerProperties.ReferenceMarket referenceMarket) {
    public static MarketMakerBusinessSettings initialDisabled() {
        var values = new MarketMakerProperties();
        return new MarketMakerBusinessSettings(values.getEngine(), values.getQuoting(), values.getRisk(),
                values.getTrade(), values.getReferenceMarket());
    }
}
