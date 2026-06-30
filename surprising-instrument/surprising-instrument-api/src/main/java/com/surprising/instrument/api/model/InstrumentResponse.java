package com.surprising.instrument.api.model;

import java.math.BigDecimal;
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
        BigDecimal contractSize,
        String contractValueAsset,
        BigDecimal priceTickSize,
        BigDecimal quantityStepSize,
        BigDecimal minOrderQty,
        BigDecimal maxOrderQty,
        BigDecimal minNotional,
        BigDecimal maxNotional,
        int pricePrecision,
        int quantityPrecision,
        List<String> supportedOrderTypes,
        List<String> supportedTimeInForce,
        boolean postOnlyEnabled,
        boolean reduceOnlyEnabled,
        boolean marketOrderEnabled,
        BigDecimal maxLeverage,
        BigDecimal initialMarginRate,
        BigDecimal maintenanceMarginRate,
        BigDecimal maxPositionNotional,
        int fundingIntervalHours,
        BigDecimal interestRate,
        BigDecimal fundingRateCap,
        BigDecimal fundingRateFloor,
        BigDecimal impactNotional,
        int minValidIndexSources,
        InstrumentStatus status,
        Instant effectiveTime,
        Instant createdAt,
        Instant updatedAt,
        List<RiskLimitBracket> riskLimitBrackets,
        List<IndexSourceConfig> indexSources) {
}
