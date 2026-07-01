package com.surprising.instrument.provider.service;

import com.surprising.instrument.api.model.IndexSourceConfig;
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
        validateBrackets(request.riskLimitBrackets());
        validateIndexSources(request.indexSources(), request.minValidIndexSources());
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

    private void requireFeeRate(String name, long value) {
        if (value < -1_000_000L || value > 1_000_000L) {
            throw new IllegalArgumentException(name + " must be within +/- 100%");
        }
    }
}
