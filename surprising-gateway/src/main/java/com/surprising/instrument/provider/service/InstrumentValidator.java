package com.surprising.instrument.provider.service;

import com.surprising.instrument.api.model.IndexSourceConfig;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.api.model.RiskLimitBracket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 校验产品、资金、风险档位和指数源间的业务不变量；原始格式由入口负责。 */
@Component
public class InstrumentValidator {

    public void validate(InstrumentUpsertRequest request) {
        if (request.baseAssetId() == request.quoteAssetId())
            throw new IllegalArgumentException("base and quote assets must differ");
        if (request.marketOrderEnabled() != request.supportedOrderTypes().contains("MARKET"))
            throw new IllegalArgumentException("marketOrderEnabled must match supportedOrderTypes MARKET");
        if (request.postOnlyEnabled() && !request.supportedOrderTypes().contains("LIMIT"))
            throw new IllegalArgumentException("postOnlyEnabled requires LIMIT");
        requireMarginRates("instrument", request.initialMarginRatePpm(), request.maintenanceMarginRatePpm());
        if (request.maxLeveragePpm() < 1_000_000L)
            throw new IllegalArgumentException("maxLeveragePpm must be at least 1000000 (1x)");
        if (request.userOpenInterestLimitRatePpm() > 1_000_000L)
            throw new IllegalArgumentException("userOpenInterestLimitRatePpm must not exceed 1000000");
        validateProductContractPair(request.instrumentType(), request.contractType());
        requirePositive("contractMultiplierPpm", request.contractMultiplierPpm());
        requirePositive("priceTickUnits", request.priceTickUnits());
        requirePositive("quantityStepUnits", request.quantityStepUnits());
        requireRange("quantity steps", request.minQuantitySteps(), request.maxQuantitySteps());
        requireRange("notional units", request.minNotionalUnits(), request.maxNotionalUnits());
        requirePositive("notionalMultiplierUnits", request.notionalMultiplierUnits());
        requirePositive("maxLeveragePpm", request.maxLeveragePpm());
        requirePositive("initialMarginRatePpm", request.initialMarginRatePpm());
        requirePositive("maintenanceMarginRatePpm", request.maintenanceMarginRatePpm());
        requireFeeRate("makerFeeRatePpm", request.makerFeeRatePpm());
        requireFeeRate("takerFeeRatePpm", request.takerFeeRatePpm());
        requirePositive("maxPositionNotionalUnits", request.maxPositionNotionalUnits());
        requireNonNegative("userOpenInterestLimitRatePpm", request.userOpenInterestLimitRatePpm());
        requirePositive("userOpenInterestLimitFloorUnits", request.userOpenInterestLimitFloorUnits());
        requirePositive("impactNotionalUnits", request.impactNotionalUnits());
        if (request.minValidIndexSources() <= 0) {
            throw new IllegalArgumentException("minValidIndexSources must be positive");
        }
        validateFundingRules(request);
        switch (request.instrumentType()) {
            case SPOT -> validateSpotRules(request);
            case PERPETUAL -> validatePerpetualRules(request);
            case DELIVERY -> validateDeliveryRules(request);
            case OPTION -> validateOptionRules(request);
        }
    }

    private void validateProductContractPair(InstrumentType instrumentType, ContractType contractType) {
        if (instrumentType == null) {
            throw new IllegalArgumentException("instrumentType is required");
        }
        if (contractType == null) {
            throw new IllegalArgumentException("contractType is required");
        }
        if (instrumentType == InstrumentType.SPOT && contractType != ContractType.SPOT) {
            throw new IllegalArgumentException("SPOT instruments must use SPOT contractType");
        }
        if (instrumentType == InstrumentType.PERPETUAL && !contractType.isPerpetual()) {
            throw new IllegalArgumentException("PERPETUAL instruments must use a perpetual contractType");
        }
        if (instrumentType == InstrumentType.DELIVERY && !contractType.isDelivery()) {
            throw new IllegalArgumentException("DELIVERY instruments must use a delivery contractType");
        }
        if (instrumentType == InstrumentType.OPTION && !contractType.isOption()) {
            throw new IllegalArgumentException("OPTION instruments must use an option contractType");
        }
    }

    private void validateSpotRules(InstrumentUpsertRequest request) {
        if (request.reduceOnlyEnabled()) {
            throw new IllegalArgumentException("spot instruments cannot enable reduce-only");
        }
        validateNonExpiringRules(request, "spot");
        if (request.riskLimitBrackets() != null && !request.riskLimitBrackets().isEmpty()) {
            throw new IllegalArgumentException("spot instruments must not define risk limit brackets");
        }
        if (request.indexSources() != null && !request.indexSources().isEmpty()) {
            validateIndexSources(request.indexSources(), request.minValidIndexSources(), request.instrumentType() == InstrumentType.OPTION);
        }
    }

    private void validatePerpetualRules(InstrumentUpsertRequest request) {
        validateNonExpiringRules(request, "perpetual");
        validateDerivativeRules(request);
    }

    private void validateDeliveryRules(InstrumentUpsertRequest request) {
        validateExpiringRules(request, "DELIVERY");
        validateDerivativeRules(request);
        if (request.strikePriceUnits() != null || request.optionType() != null
                || request.optionExerciseStyle() != null) {
            throw new IllegalArgumentException("delivery instruments must not define option metadata");
        }
    }

    private void validateOptionRules(InstrumentUpsertRequest request) {
        validateExpiringRules(request, "OPTION");
        validateDerivativeRules(request);
        if (request.underlyingInstrumentId() == null || request.underlyingInstrumentId().isBlank()) {
            throw new IllegalArgumentException("option instruments require underlyingInstrumentId");
        }
        if (request.underlyingProductLine() == null || request.underlyingProductLine() == com.surprising.product.api.ProductLine.OPTION) {
            throw new IllegalArgumentException("option underlying requires an explicit non-option product line");
        }
        requirePositive("strikePriceUnits", request.strikePriceUnits());
        if (request.strikePriceUnits() % request.priceTickUnits() != 0)
            throw new IllegalArgumentException("strikePriceUnits must align with priceTickUnits");
        if (request.optionType() == null) {
            throw new IllegalArgumentException("option instruments require optionType");
        }
        if (request.optionExerciseStyle() == null) {
            throw new IllegalArgumentException("option instruments require optionExerciseStyle");
        }
        for (var source : request.indexSources()) {
            if (!"OPTION_RISK_TICKER".equals(source.parser())
                    || source.websocketEnabled() && !"OPTION_RISK_TICKER".equals(source.websocketParser())
                    || !source.quoteCurrency().equalsIgnoreCase(source.targetQuoteCurrency()))
                throw new IllegalArgumentException("option sources require OPTION_RISK_TICKER with premium, index and same-expiry forward in the contract quote currency");
        }
    }

    private void validateDerivativeRules(InstrumentUpsertRequest request) {
        validateBrackets(request.riskLimitBrackets());
        validateIndexSources(request.indexSources(), request.minValidIndexSources(), request.instrumentType() == InstrumentType.OPTION);
        for (var bracket : request.riskLimitBrackets()) {
            if (bracket.maxLeveragePpm() > request.maxLeveragePpm())
                throw new IllegalArgumentException("risk bracket leverage exceeds instrument maximum");
        }
        if (request.riskLimitBrackets().getLast().notionalCapUnits() < request.maxPositionNotionalUnits())
            throw new IllegalArgumentException("risk brackets must cover maxPositionNotionalUnits");
    }

    private void validateExpiringRules(InstrumentUpsertRequest request, String name) {
        if (request.expiryTime() == null) {
            throw new IllegalArgumentException(name + " instruments require expiryTime");
        }
        if (request.deliveryTime() == null) {
            throw new IllegalArgumentException(name + " instruments require deliveryTime");
        }
        if (request.deliveryTime().isBefore(request.expiryTime())) {
            throw new IllegalArgumentException("deliveryTime must be greater than or equal to expiryTime");
        }
        if (request.settlementMethod() == null) {
            throw new IllegalArgumentException(name + " instruments require settlementMethod");
        }
    }

    private void validateNonExpiringRules(InstrumentUpsertRequest request, String name) {
        if (request.expiryTime() != null || request.deliveryTime() != null || request.settlementMethod() != null) {
            throw new IllegalArgumentException(name + " instruments must not define expiry or settlement metadata");
        }
        if (request.underlyingInstrumentId() != null || request.underlyingProductLine() != null) {
            throw new IllegalArgumentException(name + " instruments must not define underlyingInstrumentId");
        }
        if (request.strikePriceUnits() != null || request.optionType() != null
                || request.optionExerciseStyle() != null) {
            throw new IllegalArgumentException(name + " instruments must not define option metadata");
        }
    }

    private void validateFundingRules(InstrumentUpsertRequest request) {
        if (request.instrumentType() == InstrumentType.PERPETUAL) {
            if (request.fundingIntervalHours() <= 0 || request.fundingIntervalHours() > 24
                    || 24 % request.fundingIntervalHours() != 0) {
                throw new IllegalArgumentException("fundingIntervalHours must be one of 1, 2, 3, 4, 6, 8, 12, 24");
            }
            requireFeeRate("interestRatePpm", request.interestRatePpm());
            requireFeeRate("fundingRateCapPpm", request.fundingRateCapPpm());
            requireFeeRate("fundingRateFloorPpm", request.fundingRateFloorPpm());
            if (request.fundingRateCapPpm() < request.fundingRateFloorPpm()) {
                throw new IllegalArgumentException("fundingRateCap must be greater than or equal to fundingRateFloor");
            }
            return;
        }
        if (request.fundingIntervalHours() != 0 || request.interestRatePpm() != 0
                || request.fundingRateCapPpm() != 0 || request.fundingRateFloorPpm() != 0) {
            throw new IllegalArgumentException("non-perpetual instruments must not define funding settings");
        }
    }

    private void validateBrackets(List<RiskLimitBracket> brackets) {
        if (brackets == null || brackets.isEmpty()) {
            throw new IllegalArgumentException("at least one risk limit bracket is required");
        }
        long previousCap = 0L;
        int expected = 1;
        for (RiskLimitBracket bracket : brackets) {
            if (bracket == null) throw new IllegalArgumentException("risk bracket must not be null");
            requireMarginRates("risk bracket", bracket.initialMarginRatePpm(), bracket.maintenanceMarginRatePpm());
            if (bracket.maxLeveragePpm() < 1_000_000L)
                throw new IllegalArgumentException("risk bracket leverage must be at least 1x");
            if (bracket.bracketNo() != expected++) {
                throw new IllegalArgumentException("risk brackets must start at 1 and be contiguous");
            }
            if (bracket.notionalFloorUnits() < 0) {
                throw new IllegalArgumentException("risk bracket notionalFloor must be non-negative");
            }
            requirePositive("risk bracket notionalCapUnits", bracket.notionalCapUnits());
            if (bracket.notionalCapUnits() <= bracket.notionalFloorUnits()) {
                throw new IllegalArgumentException("risk bracket notionalCap must be greater than notionalFloor");
            }
            if (bracket.notionalFloorUnits() != previousCap) {
                throw new IllegalArgumentException("risk bracket notional ranges must be contiguous");
            }
            requirePositive("risk bracket maxLeveragePpm", bracket.maxLeveragePpm());
            requirePositive("risk bracket initialMarginRatePpm", bracket.initialMarginRatePpm());
            requirePositive("risk bracket maintenanceMarginRatePpm", bracket.maintenanceMarginRatePpm());
            requirePositive("risk bracket optionMarginFactorPpm", bracket.optionMarginFactorPpm());
            if (bracket.optionMarginFactorPpm() > 10_000_000L) {
                throw new IllegalArgumentException("risk bracket optionMarginFactorPpm exceeds limit");
            }
            previousCap = bracket.notionalCapUnits();
        }
    }

    private void validateIndexSources(List<IndexSourceConfig> sources, int minValidSources, boolean option) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("at least one index source is required");
        }
        Set<String> names = new HashSet<>();
        int enabledCount = 0;
        for (IndexSourceConfig source : sources) {
            if (source == null) throw new IllegalArgumentException("index source must not be null");
            if (option != "OPTION_RISK_TICKER".equals(source.parser()))
                throw new IllegalArgumentException("OPTION_RISK_TICKER is required exclusively for option sources");
            if (!names.add(source.source().trim().toUpperCase(java.util.Locale.ROOT))) {
                throw new IllegalArgumentException("duplicate index source: " + source.source());
            }
            if (source.enabled()) {
                enabledCount++;
            }
            requirePositive("index source weightPpm", source.weightPpm());
            if (source.fallbackWeightMultiplierPpm() < 0 || source.fallbackWeightMultiplierPpm() > 1_000_000L) {
                throw new IllegalArgumentException("index source fallbackWeightMultiplierPpm must be in [0,1000000]");
            }
        }
        if (enabledCount < minValidSources) {
            throw new IllegalArgumentException("enabled index sources must be >= minValidIndexSources");
        }
    }

    private void requireMarginRates(String name, long initial, long maintenance) {
        if (initial <= 0 || initial > 1_000_000 || maintenance <= 0 || maintenance > initial)
            throw new IllegalArgumentException(name + " requires 0 < maintenanceMarginRatePpm <= initialMarginRatePpm <= 1000000");
    }

    private void requireRange(String name, long min, long max) {
        requirePositive("min " + name, min);
        requirePositive("max " + name, max);
        if (max < min) {
            throw new IllegalArgumentException("max " + name + " must be greater than or equal to min " + name);
        }
    }

    private void requirePositive(String name, long value) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private void requirePositive(String name, Long value) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private void requireNonNegative(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private void requireFeeRate(String name, long value) {
        if (value < -1_000_000L || value > 1_000_000L) {
            throw new IllegalArgumentException(name + " must be within +/- 100%");
        }
    }
}
