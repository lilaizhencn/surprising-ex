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

@Component
public class InstrumentValidator {

    public void validate(InstrumentUpsertRequest request) {
        requireSymbol(request.symbol());
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
        if (request.fundingIntervalHours() <= 0) {
            throw new IllegalArgumentException("fundingIntervalHours must be positive");
        }
        if (request.fundingRateCapPpm() < request.fundingRateFloorPpm()) {
            throw new IllegalArgumentException("fundingRateCap must be greater than or equal to fundingRateFloor");
        }
        if (request.minValidIndexSources() <= 0) {
            throw new IllegalArgumentException("minValidIndexSources must be positive");
        }
        if (request.instrumentType() == InstrumentType.PERPETUAL) {
            validateBrackets(request.riskLimitBrackets());
            validateIndexSources(request.indexSources(), request.minValidIndexSources());
        } else {
            validateSpotRules(request);
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
    }

    private void validateSpotRules(InstrumentUpsertRequest request) {
        if (request.reduceOnlyEnabled()) {
            throw new IllegalArgumentException("spot instruments cannot enable reduce-only");
        }
        if (request.riskLimitBrackets() != null && !request.riskLimitBrackets().isEmpty()) {
            throw new IllegalArgumentException("spot instruments must not define risk limit brackets");
        }
    }

    private void validateBrackets(List<RiskLimitBracket> brackets) {
        if (brackets == null || brackets.isEmpty()) {
            throw new IllegalArgumentException("at least one risk limit bracket is required");
        }
        long previousCap = 0L;
        int expected = 1;
        for (RiskLimitBracket bracket : brackets) {
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
            previousCap = bracket.notionalCapUnits();
        }
    }

    private void validateIndexSources(List<IndexSourceConfig> sources, int minValidSources) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("at least one index source is required");
        }
        Set<String> names = new HashSet<>();
        int enabledCount = 0;
        for (IndexSourceConfig source : sources) {
            if (source.source() == null || source.source().isBlank()) {
                throw new IllegalArgumentException("index source name is required");
            }
            if (!names.add(source.source().trim().toUpperCase())) {
                throw new IllegalArgumentException("duplicate index source: " + source.source());
            }
            if (source.enabled()) {
                enabledCount++;
            }
            requirePositive("index source weightPpm", source.weightPpm());
            if (source.fallbackWeightMultiplierPpm() < 0) {
                throw new IllegalArgumentException("index source fallbackWeightMultiplierPpm must be non-negative");
            }
        }
        if (enabledCount < minValidSources) {
            throw new IllegalArgumentException("enabled index sources must be >= minValidIndexSources");
        }
    }

    private void requireSymbol(String symbol) {
        if (symbol == null || !symbol.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
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
