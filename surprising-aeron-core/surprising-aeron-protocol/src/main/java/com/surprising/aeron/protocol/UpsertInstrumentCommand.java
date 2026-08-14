package com.surprising.aeron.protocol;

import java.math.BigInteger;
import java.util.List;

public record UpsertInstrumentCommand(
        String symbol,
        long instrumentVersion,
        int contractTypeCode,
        String baseAsset,
        String quoteAsset,
        String settleAsset,
        long notionalMultiplierUnits,
        long priceTickUnits,
        long settleScaleUnits,
        long initialMarginRatePpm,
        long maintenanceMarginRatePpm,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        long expiryEpochMillis,
        int optionTypeCode,
        long strikePriceTicks,
        long maxLeveragePpm,
        long maxPositionNotionalUnits,
        long userOpenInterestLimitRatePpm,
        long userOpenInterestLimitFloorUnits,
        List<CoreRiskLimitBracket> riskLimitBrackets) {

    public UpsertInstrumentCommand {
        if (symbol == null || symbol.isBlank() || instrumentVersion <= 0 || contractTypeCode < 0
                || baseAsset == null || baseAsset.isBlank() || quoteAsset == null || quoteAsset.isBlank()
                || settleAsset == null || settleAsset.isBlank() || notionalMultiplierUnits <= 0
                || priceTickUnits <= 0 || settleScaleUnits <= 0 || initialMarginRatePpm <= 0
                || initialMarginRatePpm > 1_000_000 || maintenanceMarginRatePpm <= 0
                || maintenanceMarginRatePpm > 1_000_000
                || Math.absExact(makerFeeRatePpm) > 1_000_000 || Math.absExact(takerFeeRatePpm) > 1_000_000
                || expiryEpochMillis < 0 || optionTypeCode < -1 || strikePriceTicks < 0
                || maxLeveragePpm < 1_000_000L || maxPositionNotionalUnits <= 0
                || userOpenInterestLimitRatePpm < 0 || userOpenInterestLimitFloorUnits <= 0
                || riskLimitBrackets == null || riskLimitBrackets.isEmpty()) {
            throw new IllegalArgumentException("invalid instrument command");
        }
        riskLimitBrackets = List.copyOf(riskLimitBrackets);
        long previousCap = 0;
        for (int index = 0; index < riskLimitBrackets.size(); index++) {
            CoreRiskLimitBracket bracket = riskLimitBrackets.get(index);
            if (bracket.bracketNo() != index + 1 || bracket.notionalFloorUnits() != previousCap
                    || bracket.maxLeveragePpm() > maxLeveragePpm) {
                throw new IllegalArgumentException("risk limit brackets must be contiguous and bounded");
            }
            previousCap = bracket.notionalCapUnits();
        }
        if (previousCap < maxPositionNotionalUnits) {
            throw new IllegalArgumentException("risk limit brackets must cover max position notional");
        }
    }

    public UpsertInstrumentCommand(
            String symbol, long instrumentVersion, int contractTypeCode, String baseAsset, String quoteAsset,
            String settleAsset, long notionalMultiplierUnits, long priceTickUnits, long settleScaleUnits,
            long initialMarginRatePpm, long maintenanceMarginRatePpm, long makerFeeRatePpm, long takerFeeRatePpm,
            long expiryEpochMillis, int optionTypeCode, long strikePriceTicks) {
        this(symbol, instrumentVersion, contractTypeCode, baseAsset, quoteAsset, settleAsset,
                notionalMultiplierUnits, priceTickUnits, settleScaleUnits, initialMarginRatePpm,
                maintenanceMarginRatePpm, makerFeeRatePpm, takerFeeRatePpm, expiryEpochMillis, optionTypeCode,
                strikePriceTicks, leverageFromRate(initialMarginRatePpm), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                List.of(new CoreRiskLimitBracket(1, 0, Long.MAX_VALUE, leverageFromRate(initialMarginRatePpm),
                        initialMarginRatePpm, maintenanceMarginRatePpm)));
    }

    private static long leverageFromRate(long ratePpm) {
        return BigInteger.valueOf(1_000_000L).multiply(BigInteger.valueOf(1_000_000L))
                .divide(BigInteger.valueOf(ratePpm)).longValueExact();
    }
}
