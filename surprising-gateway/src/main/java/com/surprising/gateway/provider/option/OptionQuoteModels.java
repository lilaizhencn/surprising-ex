package com.surprising.gateway.provider.option;

import java.math.BigDecimal;
import java.time.Instant;

public final class OptionQuoteModels {

    private OptionQuoteModels() {
    }

    public record OptionQuoteResponse(
            String symbol,
            String underlyingSymbol,
            String optionType,
            Instant expiryTime,
            Instant asOf,
            BigDecimal underlyingPrice,
            BigDecimal optionPrice,
            BigDecimal strikePrice,
            BigDecimal timeToExpiryYears,
            BigDecimal impliedVolatility,
            BigDecimal delta,
            BigDecimal gamma,
            BigDecimal thetaPerYear,
            BigDecimal vega,
            BigDecimal rho) {
    }
}
