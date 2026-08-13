package com.surprising.aeron.service.state;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record CoreTreasuryState(
        Map<String, Long> feeBalances,
        Map<String, Long> insuranceBalances,
        Map<String, Long> insuranceDeficits,
        Map<String, Long> fundingSettlements,
        Map<String, Long> lifecycleSettlements) {

    public CoreTreasuryState {
        if (feeBalances == null || insuranceBalances == null || insuranceDeficits == null
                || fundingSettlements == null || lifecycleSettlements == null) {
            throw new IllegalArgumentException("invalid treasury state");
        }
        feeBalances = normalized(feeBalances, true);
        insuranceBalances = normalized(insuranceBalances, false);
        insuranceDeficits = normalized(insuranceDeficits, false);
        fundingSettlements = markers(fundingSettlements);
        lifecycleSettlements = markers(lifecycleSettlements);
    }

    public static CoreTreasuryState empty() {
        return new CoreTreasuryState(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    public CoreTreasuryState adjustFee(String asset, long deltaUnits) {
        return new CoreTreasuryState(adjust(feeBalances, asset, deltaUnits, true),
                insuranceBalances, insuranceDeficits, fundingSettlements, lifecycleSettlements);
    }

    public CoreTreasuryState adjustInsurance(String asset, long deltaUnits) {
        String normalizedAsset = AssetBalance.normalizeAsset(asset);
        long current = Math.subtractExact(insuranceBalances.getOrDefault(normalizedAsset, 0L),
                insuranceDeficits.getOrDefault(normalizedAsset, 0L));
        long next = Math.addExact(current, deltaUnits);
        Map<String, Long> balances = new TreeMap<>(insuranceBalances);
        Map<String, Long> deficits = new TreeMap<>(insuranceDeficits);
        if (next >= 0) {
            putOrRemove(balances, normalizedAsset, next);
            deficits.remove(normalizedAsset);
        } else {
            balances.remove(normalizedAsset);
            deficits.put(normalizedAsset, Math.negateExact(next));
        }
        return new CoreTreasuryState(feeBalances, balances, deficits, fundingSettlements, lifecycleSettlements);
    }

    public CoreTreasuryState recordFunding(String symbol, long settlementId) {
        return new CoreTreasuryState(feeBalances, insuranceBalances, insuranceDeficits,
                recordMarker(fundingSettlements, symbol, settlementId), lifecycleSettlements);
    }

    public CoreTreasuryState recordLifecycle(String symbol, long settlementId) {
        return new CoreTreasuryState(feeBalances, insuranceBalances, insuranceDeficits,
                fundingSettlements, recordMarker(lifecycleSettlements, symbol, settlementId));
    }

    private static Map<String, Long> adjust(
            Map<String, Long> source,
            String asset,
            long deltaUnits,
            boolean signed) {
        String normalizedAsset = AssetBalance.normalizeAsset(asset);
        long next = Math.addExact(source.getOrDefault(normalizedAsset, 0L), deltaUnits);
        if (!signed && next < 0) {
            throw new IllegalArgumentException("treasury balance must not be negative");
        }
        Map<String, Long> result = new TreeMap<>(source);
        putOrRemove(result, normalizedAsset, next);
        return result;
    }

    private static Map<String, Long> normalized(Map<String, Long> source, boolean signed) {
        Map<String, Long> result = new TreeMap<>();
        source.forEach((asset, units) -> {
            String normalizedAsset = AssetBalance.normalizeAsset(asset);
            if (units == null || (!signed && units < 0)) {
                throw new IllegalArgumentException("invalid treasury units");
            }
            if (units != 0 && result.put(normalizedAsset, units) != null) {
                throw new IllegalArgumentException("duplicate treasury asset");
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Long> markers(Map<String, Long> source) {
        Map<String, Long> result = new TreeMap<>();
        source.forEach((symbol, settlementId) -> {
            String normalizedSymbol = OrderReservation.normalizeSymbol(symbol);
            if (settlementId == null || settlementId <= 0 || result.put(normalizedSymbol, settlementId) != null) {
                throw new IllegalArgumentException("invalid settlement marker");
            }
        });
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Long> recordMarker(Map<String, Long> source, String symbol, long settlementId) {
        String normalizedSymbol = OrderReservation.normalizeSymbol(symbol);
        long current = source.getOrDefault(normalizedSymbol, 0L);
        if (settlementId <= current) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "settlement id must increase");
        }
        Map<String, Long> result = new TreeMap<>(source);
        result.put(normalizedSymbol, settlementId);
        return result;
    }

    private static void putOrRemove(Map<String, Long> values, String asset, long units) {
        if (units == 0) {
            values.remove(asset);
        } else {
            values.put(asset, units);
        }
    }
}
