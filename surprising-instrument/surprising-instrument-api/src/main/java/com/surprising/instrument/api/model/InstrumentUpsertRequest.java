package com.surprising.instrument.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record InstrumentUpsertRequest(
        @NotBlank String symbol,
        @NotNull InstrumentType instrumentType,
        @NotNull ContractType contractType,
        @NotBlank String baseAsset,
        @NotBlank String quoteAsset,
        @NotBlank String settleAsset,
        @Positive BigDecimal contractSize,
        @NotBlank String contractValueAsset,
        @Positive BigDecimal priceTickSize,
        @Positive BigDecimal quantityStepSize,
        @Positive BigDecimal minOrderQty,
        @Positive BigDecimal maxOrderQty,
        @Positive BigDecimal minNotional,
        @Positive BigDecimal maxNotional,
        int pricePrecision,
        int quantityPrecision,
        List<String> supportedOrderTypes,
        List<String> supportedTimeInForce,
        boolean postOnlyEnabled,
        boolean reduceOnlyEnabled,
        boolean marketOrderEnabled,
        @Positive BigDecimal maxLeverage,
        @Positive BigDecimal initialMarginRate,
        @Positive BigDecimal maintenanceMarginRate,
        @Positive BigDecimal maxPositionNotional,
        int fundingIntervalHours,
        BigDecimal interestRate,
        BigDecimal fundingRateCap,
        BigDecimal fundingRateFloor,
        @Positive BigDecimal impactNotional,
        int minValidIndexSources,
        @NotNull InstrumentStatus status,
        Instant effectiveTime,
        List<RiskLimitBracket> riskLimitBrackets,
        List<IndexSourceConfig> indexSources) {
}
