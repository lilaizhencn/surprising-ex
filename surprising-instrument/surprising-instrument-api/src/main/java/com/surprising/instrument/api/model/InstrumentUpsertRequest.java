package com.surprising.instrument.api.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.List;

public record InstrumentUpsertRequest(
        @NotBlank String symbol,
        @NotNull InstrumentType instrumentType,
        @NotNull ContractType contractType,
        @NotBlank String baseAsset,
        @NotBlank String quoteAsset,
        @NotBlank String settleAsset,
        @Positive long contractMultiplierPpm,
        @NotBlank String contractValueAsset,
        @Positive long priceTickUnits,
        @Positive long quantityStepUnits,
        @Positive long minQuantitySteps,
        @Positive long maxQuantitySteps,
        @Positive long minNotionalUnits,
        @Positive long maxNotionalUnits,
        @Positive long notionalMultiplierUnits,
        int pricePrecision,
        int quantityPrecision,
        List<String> supportedOrderTypes,
        List<String> supportedTimeInForce,
        boolean postOnlyEnabled,
        boolean reduceOnlyEnabled,
        boolean marketOrderEnabled,
        @Positive long maxLeveragePpm,
        @Positive long initialMarginRatePpm,
        @Positive long maintenanceMarginRatePpm,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        @Positive long maxPositionNotionalUnits,
        @PositiveOrZero long userOpenInterestLimitRatePpm,
        @Positive long userOpenInterestLimitFloorUnits,
        int fundingIntervalHours,
        long interestRatePpm,
        long fundingRateCapPpm,
        long fundingRateFloorPpm,
        @Positive long impactNotionalUnits,
        int minValidIndexSources,
        @NotNull InstrumentStatus status,
        Instant effectiveTime,
        List<RiskLimitBracket> riskLimitBrackets,
        List<IndexSourceConfig> indexSources) {
}
