package com.surprising.instrument.api.model;

import java.time.Instant;
import java.util.List;

public record InstrumentResponse(
        String symbol,
        long version,
        InstrumentType instrumentType,
        ContractType contractType,
        String baseAsset,
        String quoteAsset,
        String settleAsset,
        long contractMultiplierPpm,
        String contractValueAsset,
        long priceTickUnits,
        long quantityStepUnits,
        long minQuantitySteps,
        long maxQuantitySteps,
        long minNotionalUnits,
        long maxNotionalUnits,
        long notionalMultiplierUnits,
        int pricePrecision,
        int quantityPrecision,
        List<String> supportedOrderTypes,
        List<String> supportedTimeInForce,
        boolean postOnlyEnabled,
        boolean reduceOnlyEnabled,
        boolean marketOrderEnabled,
        long maxLeveragePpm,
        long initialMarginRatePpm,
        long maintenanceMarginRatePpm,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        long maxPositionNotionalUnits,
        long userOpenInterestLimitRatePpm,
        long userOpenInterestLimitFloorUnits,
        int fundingIntervalHours,
        long interestRatePpm,
        long fundingRateCapPpm,
        long fundingRateFloorPpm,
        long impactNotionalUnits,
        int minValidIndexSources,
        Instant expiryTime,
        Instant deliveryTime,
        String underlyingSymbol,
        Long strikePriceUnits,
        OptionType optionType,
        OptionExerciseStyle optionExerciseStyle,
        ContractSettlementMethod settlementMethod,
        InstrumentStatus status,
        Instant effectiveTime,
        Instant createdAt,
        Instant updatedAt,
        List<RiskLimitBracket> riskLimitBrackets,
        List<IndexSourceConfig> indexSources) {

    /**
     * 创建不可变的完整合约快照，避免下游缓存保留可变集合引用。
     */
    public static InstrumentResponse immutableCopy(InstrumentResponse value) {
        if (value == null) {
            throw new IllegalArgumentException("instrument snapshot is required");
        }
        return new InstrumentResponse(
                value.symbol(), value.version(), value.instrumentType(), value.contractType(),
                value.baseAsset(), value.quoteAsset(), value.settleAsset(), value.contractMultiplierPpm(),
                value.contractValueAsset(), value.priceTickUnits(), value.quantityStepUnits(),
                value.minQuantitySteps(), value.maxQuantitySteps(), value.minNotionalUnits(),
                value.maxNotionalUnits(), value.notionalMultiplierUnits(), value.pricePrecision(),
                value.quantityPrecision(),
                value.supportedOrderTypes() == null ? List.of() : List.copyOf(value.supportedOrderTypes()),
                value.supportedTimeInForce() == null ? List.of() : List.copyOf(value.supportedTimeInForce()),
                value.postOnlyEnabled(), value.reduceOnlyEnabled(), value.marketOrderEnabled(),
                value.maxLeveragePpm(), value.initialMarginRatePpm(), value.maintenanceMarginRatePpm(),
                value.makerFeeRatePpm(), value.takerFeeRatePpm(), value.maxPositionNotionalUnits(),
                value.userOpenInterestLimitRatePpm(), value.userOpenInterestLimitFloorUnits(),
                value.fundingIntervalHours(), value.interestRatePpm(), value.fundingRateCapPpm(),
                value.fundingRateFloorPpm(), value.impactNotionalUnits(), value.minValidIndexSources(),
                value.expiryTime(), value.deliveryTime(), value.underlyingSymbol(), value.strikePriceUnits(),
                value.optionType(), value.optionExerciseStyle(), value.settlementMethod(), value.status(),
                value.effectiveTime(), value.createdAt(), value.updatedAt(),
                value.riskLimitBrackets() == null ? List.of() : List.copyOf(value.riskLimitBrackets()),
                value.indexSources() == null ? List.of() : List.copyOf(value.indexSources()));
    }
}
