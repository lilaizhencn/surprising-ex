package com.surprising.aeron.service.state;

import java.util.Map;
import java.util.Collections;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

public record CoreTreasuryState(
        Map<String, Long> feeBalances,
        Map<String, Long> insuranceBalances,
        Map<String, Long> insuranceDeficits,
        Map<String, Long> fundingSettlements,
        Map<String, Long> lifecycleSettlements,
        Map<String, FundingProgress> fundingProgress,
        Map<String, LifecycleProgress> lifecycleProgress) {

    public CoreTreasuryState {
        if (feeBalances == null || insuranceBalances == null || insuranceDeficits == null
                || fundingSettlements == null || lifecycleSettlements == null || fundingProgress == null
                || lifecycleProgress == null) {
            throw new IllegalArgumentException("invalid treasury state");
        }
        feeBalances = normalized(feeBalances, true);
        insuranceBalances = normalized(insuranceBalances, false);
        insuranceDeficits = normalized(insuranceDeficits, false);
        fundingSettlements = markers(fundingSettlements);
        lifecycleSettlements = markers(lifecycleSettlements);
        fundingProgress = progresses(fundingProgress);
        lifecycleProgress = lifecycleProgresses(lifecycleProgress);
    }

    public CoreTreasuryState(Map<String, Long> feeBalances,
                             Map<String, Long> insuranceBalances,
                             Map<String, Long> insuranceDeficits,
                             Map<String, Long> fundingSettlements,
                             Map<String, Long> lifecycleSettlements) {
        this(feeBalances, insuranceBalances, insuranceDeficits, fundingSettlements, lifecycleSettlements,
                Map.of(), Map.of());
    }

    public CoreTreasuryState(Map<String, Long> feeBalances,
                             Map<String, Long> insuranceBalances,
                             Map<String, Long> insuranceDeficits,
                             Map<String, Long> fundingSettlements,
                             Map<String, Long> lifecycleSettlements,
                             Map<String, FundingProgress> fundingProgress) {
        this(feeBalances, insuranceBalances, insuranceDeficits, fundingSettlements, lifecycleSettlements,
                fundingProgress, Map.of());
    }

    public static CoreTreasuryState empty() {
        return new CoreTreasuryState(Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    public CoreTreasuryState adjustFee(String asset, long deltaUnits) {
        return new CoreTreasuryState(adjust(feeBalances, asset, deltaUnits, true),
                insuranceBalances, insuranceDeficits, fundingSettlements, lifecycleSettlements,
                fundingProgress, lifecycleProgress);
    }

    public CoreTreasuryState adjustInsurance(String asset, long deltaUnits) {
        String normalizedAsset = AssetBalance.normalizeAsset(asset);
        long current = Math.subtractExact(insuranceBalances.getOrDefault(normalizedAsset, 0L),
                insuranceDeficits.getOrDefault(normalizedAsset, 0L));
        long next = Math.addExact(current, deltaUnits);
        Map<String, Long> balances = StateMapSupport.delta(insuranceBalances);
        Map<String, Long> deficits = StateMapSupport.delta(insuranceDeficits);
        if (next >= 0) {
            putOrRemove(balances, normalizedAsset, next);
            deficits.remove(normalizedAsset);
        } else {
            balances.remove(normalizedAsset);
            deficits.put(normalizedAsset, Math.negateExact(next));
        }
        return new CoreTreasuryState(feeBalances, balances, deficits, fundingSettlements, lifecycleSettlements,
                fundingProgress, lifecycleProgress);
    }

    public CoreTreasuryState recordFunding(String symbol, long settlementId) {
        Map<String, FundingProgress> progress = StateMapSupport.delta(fundingProgress);
        progress.remove(OrderReservation.normalizeSymbol(symbol));
        return new CoreTreasuryState(feeBalances, insuranceBalances, insuranceDeficits,
                recordMarker(fundingSettlements, symbol, settlementId), lifecycleSettlements, progress,
                lifecycleProgress);
    }

    public CoreTreasuryState recordLifecycle(String symbol, long settlementId) {
        return new CoreTreasuryState(feeBalances, insuranceBalances, insuranceDeficits,
                fundingSettlements, recordMarker(lifecycleSettlements, symbol, settlementId), fundingProgress,
                clearLifecycleProgress(symbol));
    }

    public FundingProgress fundingProgress(String symbol) {
        return fundingProgress.get(OrderReservation.normalizeSymbol(symbol));
    }

    public LifecycleProgress lifecycleProgress(String symbol) {
        return lifecycleProgress.get(OrderReservation.normalizeSymbol(symbol));
    }

    public long fundingSettlement(String symbol) {
        return fundingSettlements.getOrDefault(OrderReservation.normalizeSymbol(symbol), 0L);
    }

    public long lifecycleSettlement(String symbol) {
        return lifecycleSettlements.getOrDefault(OrderReservation.normalizeSymbol(symbol), 0L);
    }

    public CoreTreasuryState withFundingProgress(String symbol, FundingProgress progress) {
        String normalizedSymbol = OrderReservation.normalizeSymbol(symbol);
        Map<String, FundingProgress> next = StateMapSupport.delta(fundingProgress);
        if (progress == null) next.remove(normalizedSymbol);
        else next.put(normalizedSymbol, progress);
        return new CoreTreasuryState(feeBalances, insuranceBalances, insuranceDeficits,
                fundingSettlements, lifecycleSettlements, next, lifecycleProgress);
    }

    public CoreTreasuryState withLifecycleProgress(String symbol, LifecycleProgress progress) {
        String normalizedSymbol = OrderReservation.normalizeSymbol(symbol);
        Map<String, LifecycleProgress> next = StateMapSupport.delta(lifecycleProgress);
        if (progress == null) next.remove(normalizedSymbol);
        else next.put(normalizedSymbol, progress);
        return new CoreTreasuryState(feeBalances, insuranceBalances, insuranceDeficits,
                fundingSettlements, lifecycleSettlements, fundingProgress, next);
    }

    public Set<String> changedAssets() {
        Set<String> fee = StateMapSupport.changedKeys(feeBalances);
        Set<String> insurance = StateMapSupport.changedKeys(insuranceBalances);
        Set<String> deficits = StateMapSupport.changedKeys(insuranceDeficits);
        TreeSet<String> assets = new TreeSet<>();
        assets.addAll(fee);
        assets.addAll(insurance);
        assets.addAll(deficits);
        return Collections.unmodifiableSet(assets);
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
        Map<String, Long> result = StateMapSupport.delta(source);
        putOrRemove(result, normalizedAsset, next);
        return result;
    }

    private static Map<String, Long> normalized(Map<String, Long> source, boolean signed) {
        if (StateMapSupport.isFrozen(source)) return source;
        if (StateMapSupport.isDelta(source)) {
            for (Object key : StateMapSupport.changedKeys(source)) {
                if (source.containsKey(key)) {
                    validateNormalized((String) key, source.get(key), signed);
                }
            }
            return StateMapSupport.freezeSorted(source);
        }
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
        return StateMapSupport.freezeSorted(result);
    }

    private static Map<String, Long> markers(Map<String, Long> source) {
        if (StateMapSupport.isFrozen(source)) return source;
        if (StateMapSupport.isDelta(source)) {
            for (Object key : StateMapSupport.changedKeys(source)) {
                if (source.containsKey(key)) {
                    Long settlementId = source.get(key);
                    if (!OrderReservation.normalizeSymbol((String) key).equals(key)
                            || settlementId == null || settlementId <= 0) {
                        throw new IllegalArgumentException("invalid settlement marker");
                    }
                }
            }
            return StateMapSupport.freezeSorted(source);
        }
        Map<String, Long> result = new TreeMap<>();
        source.forEach((symbol, settlementId) -> {
            String normalizedSymbol = OrderReservation.normalizeSymbol(symbol);
            if (settlementId == null || settlementId <= 0 || result.put(normalizedSymbol, settlementId) != null) {
                throw new IllegalArgumentException("invalid settlement marker");
            }
        });
        return StateMapSupport.freezeSorted(result);
    }

    private static Map<String, FundingProgress> progresses(Map<String, FundingProgress> source) {
        if (StateMapSupport.isFrozen(source)) return source;
        if (StateMapSupport.isDelta(source)) {
            for (Object key : StateMapSupport.changedKeys(source)) {
                if (source.containsKey(key)) validateProgress((String) key, source.get(key));
            }
            return StateMapSupport.freezeSorted(source);
        }
        Map<String, FundingProgress> result = new TreeMap<>();
        source.forEach((symbol, progress) -> {
            String normalizedSymbol = OrderReservation.normalizeSymbol(symbol);
            validateProgress(normalizedSymbol, progress);
            if (result.put(normalizedSymbol, progress) != null) {
                throw new IllegalArgumentException("duplicate funding progress");
            }
        });
        return StateMapSupport.freezeSorted(result);
    }

    private static Map<String, LifecycleProgress> lifecycleProgresses(Map<String, LifecycleProgress> source) {
        if (StateMapSupport.isFrozen(source)) return source;
        if (StateMapSupport.isDelta(source)) {
            for (Object key : StateMapSupport.changedKeys(source)) {
                if (source.containsKey(key)) validateLifecycleProgress((String) key, source.get(key));
            }
            return StateMapSupport.freezeSorted(source);
        }
        Map<String, LifecycleProgress> result = new TreeMap<>();
        source.forEach((symbol, progress) -> {
            String normalizedSymbol = OrderReservation.normalizeSymbol(symbol);
            validateLifecycleProgress(normalizedSymbol, progress);
            if (result.put(normalizedSymbol, progress) != null) {
                throw new IllegalArgumentException("duplicate lifecycle progress");
            }
        });
        return StateMapSupport.freezeSorted(result);
    }

    private Map<String, LifecycleProgress> clearLifecycleProgress(String symbol) {
        Map<String, LifecycleProgress> next = StateMapSupport.delta(lifecycleProgress);
        next.remove(OrderReservation.normalizeSymbol(symbol));
        return next;
    }

    private static Map<String, Long> recordMarker(Map<String, Long> source, String symbol, long settlementId) {
        String normalizedSymbol = OrderReservation.normalizeSymbol(symbol);
        long current = source.getOrDefault(normalizedSymbol, 0L);
        if (settlementId <= current) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "settlement id must increase");
        }
        Map<String, Long> result = StateMapSupport.delta(source);
        result.put(normalizedSymbol, settlementId);
        return result;
    }

    private static void validateNormalized(String asset, Long units, boolean signed) {
        if (!AssetBalance.normalizeAsset(asset).equals(asset)
                || units == null || (!signed && units < 0)) {
            throw new IllegalArgumentException("invalid treasury units");
        }
    }

    private static void validateProgress(String symbol, FundingProgress progress) {
        if (!OrderReservation.normalizeSymbol(symbol).equals(symbol) || progress == null) {
            throw new IllegalArgumentException("invalid funding progress");
        }
    }

    private static void validateLifecycleProgress(String symbol, LifecycleProgress progress) {
        if (!OrderReservation.normalizeSymbol(symbol).equals(symbol) || progress == null) {
            throw new IllegalArgumentException("invalid lifecycle progress");
        }
    }

    private static void putOrRemove(Map<String, Long> values, String asset, long units) {
        if (units == 0) {
            values.remove(asset);
        } else {
            values.put(asset, units);
        }
    }

    public record FundingProgress(long settlementId, long instrumentVersion, long fundingRatePpm,
                                 long nextCursorUserId, UUID commandId) {
        public FundingProgress {
            if (settlementId <= 0 || instrumentVersion <= 0 || Math.absExact(fundingRatePpm) > 1_000_000
                    || nextCursorUserId < 0 || commandId == null) {
                throw new IllegalArgumentException("invalid funding progress");
            }
        }
    }

    public record LifecycleProgress(long settlementId, long instrumentVersion, long settlementPriceTicks,
                                   long optionCashUnitsPerContract, long nextCursorUserId, UUID commandId) {
        public LifecycleProgress {
            if (settlementId <= 0 || instrumentVersion <= 0 || settlementPriceTicks < 0
                    || optionCashUnitsPerContract < 0 || nextCursorUserId < 0 || commandId == null) {
                throw new IllegalArgumentException("invalid lifecycle progress");
            }
        }
    }
}
