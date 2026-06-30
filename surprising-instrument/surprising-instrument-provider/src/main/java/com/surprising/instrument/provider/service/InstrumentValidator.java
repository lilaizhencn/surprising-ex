package com.surprising.instrument.provider.service;

import com.surprising.instrument.api.model.IndexSourceConfig;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.api.model.RiskLimitBracket;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class InstrumentValidator {

    public void validate(InstrumentUpsertRequest request) {
        requireSymbol(request.symbol());
        requirePositive("contractSize", request.contractSize());
        requirePositive("priceTickSize", request.priceTickSize());
        requirePositive("quantityStepSize", request.quantityStepSize());
        requireRange("orderQty", request.minOrderQty(), request.maxOrderQty());
        requireRange("notional", request.minNotional(), request.maxNotional());
        requirePositive("maxLeverage", request.maxLeverage());
        requirePositive("initialMarginRate", request.initialMarginRate());
        requirePositive("maintenanceMarginRate", request.maintenanceMarginRate());
        requirePositive("maxPositionNotional", request.maxPositionNotional());
        requirePositive("impactNotional", request.impactNotional());
        if (request.fundingIntervalHours() <= 0) {
            throw new IllegalArgumentException("fundingIntervalHours must be positive");
        }
        if (request.fundingRateCap() == null || request.fundingRateFloor() == null
                || request.fundingRateCap().compareTo(request.fundingRateFloor()) < 0) {
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
        BigDecimal previousCap = BigDecimal.ZERO;
        int expected = 1;
        for (RiskLimitBracket bracket : brackets) {
            if (bracket.bracketNo() != expected++) {
                throw new IllegalArgumentException("risk brackets must start at 1 and be contiguous");
            }
            if (bracket.notionalFloor() == null || bracket.notionalFloor().signum() < 0) {
                throw new IllegalArgumentException("risk bracket notionalFloor must be non-negative");
            }
            requirePositive("risk bracket notionalCap", bracket.notionalCap());
            if (bracket.notionalCap().compareTo(bracket.notionalFloor()) <= 0) {
                throw new IllegalArgumentException("risk bracket notionalCap must be greater than notionalFloor");
            }
            if (bracket.notionalFloor().compareTo(previousCap) != 0) {
                throw new IllegalArgumentException("risk bracket notional ranges must be contiguous");
            }
            requirePositive("risk bracket maxLeverage", bracket.maxLeverage());
            requirePositive("risk bracket initialMarginRate", bracket.initialMarginRate());
            requirePositive("risk bracket maintenanceMarginRate", bracket.maintenanceMarginRate());
            previousCap = bracket.notionalCap();
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
            requirePositive("index source weight", source.weight());
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

    private void requireRange(String name, BigDecimal min, BigDecimal max) {
        requirePositive("min " + name, min);
        requirePositive("max " + name, max);
        if (max.compareTo(min) < 0) {
            throw new IllegalArgumentException("max " + name + " must be greater than or equal to min " + name);
        }
    }

    private void requirePositive(String name, BigDecimal value) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
