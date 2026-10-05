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
        if (request == null) throw new IllegalArgumentException("instrument configuration is required");
        requireSymbol(request.symbol());
        if (request.instrumentId() != null) requirePositive("instrumentId", request.instrumentId());
        requirePositive("baseAssetId", request.baseAssetId());
        requirePositive("quoteAssetId", request.quoteAssetId());
        requirePositive("settleAssetId", request.settleAssetId());
        requirePositive("contractValueAssetId", request.contractValueAssetId());
        if (request.baseAssetId() == request.quoteAssetId())
            throw new IllegalArgumentException("base and quote assets must differ");
        if (request.status() == null) throw new IllegalArgumentException("status is required");
        requirePrecision("pricePrecision", request.pricePrecision());
        requirePrecision("quantityPrecision", request.quantityPrecision());
        requireChoices("supportedOrderTypes", request.supportedOrderTypes(), Set.of("LIMIT", "MARKET"));
        requireChoices("supportedTimeInForce", request.supportedTimeInForce(), Set.of("GTC", "IOC", "FOK", "GTX"));
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
        com.surprising.product.api.InstrumentIds.parse(request.underlyingInstrumentId());
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
            requireUrl("index source baseUrl", source.baseUrl(), Set.of("https", "http"));
            if (source.path() == null || !source.path().startsWith("/") || source.path().startsWith("//"))
                throw new IllegalArgumentException("index source path must start with a single slash");
            if (source.sourceSymbol() == null || source.sourceSymbol().isBlank()
                    || source.parser() == null || source.parser().isBlank())
                throw new IllegalArgumentException("index source symbol and parser are required");
            if (source.quoteCurrency() == null || source.quoteCurrency().isBlank()
                    || source.targetQuoteCurrency() == null || source.targetQuoteCurrency().isBlank())
                throw new IllegalArgumentException("index source quote currencies are required");
            requireParser(source.parser());
            if (option != "OPTION_RISK_TICKER".equals(source.parser()))
                throw new IllegalArgumentException("OPTION_RISK_TICKER is required exclusively for option sources");
            if (!source.quoteCurrency().equalsIgnoreCase(source.targetQuoteCurrency())) {
                requireUrl("conversionBaseUrl", source.conversionBaseUrl(), Set.of("https", "http"));
                if (source.conversionPath() == null || !source.conversionPath().startsWith("/")
                        || source.conversionPath().startsWith("//"))
                    throw new IllegalArgumentException("conversionPath must start with a single slash");
                requireParser(source.conversionParser());
            }
            if (source.websocketEnabled()) {
                requireParser(source.websocketParser());
                requireUrl("index source websocketUrl", source.websocketUrl(), Set.of("wss", "ws"));
                if (source.websocketParser() == null || source.websocketParser().isBlank()
                        || source.websocketSubscribeMessage() == null || source.websocketSubscribeMessage().isBlank())
                    throw new IllegalArgumentException("WebSocket parser and subscription message are required");
                try { new tools.jackson.databind.ObjectMapper().readTree(source.websocketSubscribeMessage()); }
                catch (RuntimeException error) { throw new IllegalArgumentException("WebSocket subscription message must be valid JSON"); }
            }
            if (source.conversionMode() == null || !Set.of("DISCOUNT", "DISABLE").contains(source.conversionMode()))
                throw new IllegalArgumentException("conversionMode must be DISCOUNT or DISABLE");
            if (source.conversionOperation() == null || !Set.of("MULTIPLY", "DIVIDE").contains(source.conversionOperation()))
                throw new IllegalArgumentException("conversionOperation must be MULTIPLY or DIVIDE");
            if (source.source() == null || source.source().isBlank()) {
                throw new IllegalArgumentException("index source name is required");
            }
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

    private void requireParser(String parser) {
        if (parser == null || !Set.of("BINANCE_BOOK_TICKER", "OKX_TICKER", "OKX_INDEX_TICKER",
                "BYBIT_TICKER", "COINBASE_TICKER", "KRAKEN_TICKER", "OPTION_RISK_TICKER").contains(parser))
            throw new IllegalArgumentException("unsupported index source parser: " + parser);
    }

    private void requirePrecision(String name, int value) {
        if (value < 0 || value > 18) throw new IllegalArgumentException(name + " must be in [0,18]");
    }

    private void requireChoices(String name, List<String> values, Set<String> allowed) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(v -> v == null || !allowed.contains(v))
                || new HashSet<>(values).size() != values.size())
            throw new IllegalArgumentException(name + " must contain unique supported values: " + allowed);
    }

    private void requireMarginRates(String name, long initial, long maintenance) {
        if (initial <= 0 || initial > 1_000_000 || maintenance <= 0 || maintenance > initial)
            throw new IllegalArgumentException(name + " requires 0 < maintenanceMarginRatePpm <= initialMarginRatePpm <= 1000000");
    }

    private void requireUrl(String name, String value, Set<String> schemes) {
        try {
            var uri = java.net.URI.create(value);
            if (!schemes.contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getFragment() != null) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(name + " must be an absolute URL using " + schemes);
        }
    }

    private void requireSymbol(String symbol) {
        if (symbol == null || symbol.endsWith("-SWAP") || !symbol.matches("[A-Z0-9][A-Z0-9_-]{1,63}")) {
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
