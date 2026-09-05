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
        Map<String, Long> liquidationFeeBalances,
        Map<String, Long> fundingResidualBalances,
        Map<String, Long> roundingResidualBalances,
        Map<String, Long> clearingPnlBalances,
        Map<String, Long> fundingSettlements,
        Map<String, Long> lifecycleSettlements,
        Map<String, FundingProgress> fundingProgress,
        Map<String, LifecycleProgress> lifecycleProgress) {

    public CoreTreasuryState {
        if (feeBalances == null || insuranceBalances == null || insuranceDeficits == null
                || liquidationFeeBalances == null || fundingResidualBalances == null
                || roundingResidualBalances == null || clearingPnlBalances == null
                || fundingSettlements == null || lifecycleSettlements == null || fundingProgress == null
                || lifecycleProgress == null) {
            throw new IllegalArgumentException("invalid treasury state");
        }
        feeBalances = normalized(feeBalances, true);
        insuranceBalances = normalized(insuranceBalances, true);
        insuranceDeficits = normalized(insuranceDeficits, true);
        liquidationFeeBalances = normalized(liquidationFeeBalances, true);
        fundingResidualBalances = normalized(fundingResidualBalances, true);
        roundingResidualBalances = normalized(roundingResidualBalances, true);
        clearingPnlBalances = normalized(clearingPnlBalances, true);
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

    public CoreTreasuryState(Map<String, Long> feeBalances,
                             Map<String, Long> insuranceBalances,
                             Map<String, Long> insuranceDeficits,
                             Map<String, Long> fundingSettlements,
                             Map<String, Long> lifecycleSettlements,
                             Map<String, FundingProgress> fundingProgress,
                             Map<String, LifecycleProgress> lifecycleProgress) {
        this(feeBalances, insuranceBalances, insuranceDeficits,
                Map.of(), Map.of(), Map.of(), Map.of(),
                fundingSettlements, lifecycleSettlements, fundingProgress, lifecycleProgress);
    }

    public static CoreTreasuryState ofSubledgers(
            Map<String, Long> feeBalances,
            Map<String, Long> insuranceBalances,
            Map<String, Long> liquidationFeeBalances,
            Map<String, Long> fundingResidualBalances,
            Map<String, Long> roundingResidualBalances,
            Map<String, Long> clearingPnlBalances,
            Map<String, Long> deficitBalances) {
        return new CoreTreasuryState(feeBalances, insuranceBalances, deficitBalances,
                liquidationFeeBalances, fundingResidualBalances, roundingResidualBalances, clearingPnlBalances,
                Map.of(), Map.of(), Map.of(), Map.of());
    }

    public static CoreTreasuryState empty() {
        return ofSubledgers(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    public Map<String, Long> deficitBalances() {
        return insuranceDeficits;
    }

    public CoreTreasuryState adjustFee(String asset, long deltaUnits) {
        return new CoreTreasuryState(adjust(feeBalances, asset, deltaUnits, true),
                insuranceBalances, insuranceDeficits, liquidationFeeBalances, fundingResidualBalances,
                roundingResidualBalances, clearingPnlBalances, fundingSettlements, lifecycleSettlements,
                fundingProgress, lifecycleProgress);
    }

    public CoreTreasuryState adjustInsurance(String asset, long deltaUnits) {
        return new CoreTreasuryState(feeBalances, adjust(insuranceBalances, asset, deltaUnits, true),
                insuranceDeficits, liquidationFeeBalances, fundingResidualBalances, roundingResidualBalances,
                clearingPnlBalances, fundingSettlements, lifecycleSettlements,
                fundingProgress, lifecycleProgress);
    }

    public CoreTreasuryState adjustLiquidationFee(String asset, long deltaUnits) {
        return withSubledger(liquidationFeeBalances, adjust(liquidationFeeBalances, asset, deltaUnits, true), 0);
    }

    public CoreTreasuryState adjustFundingResidual(String asset, long deltaUnits) {
        return withSubledger(fundingResidualBalances, adjust(fundingResidualBalances, asset, deltaUnits, true), 1);
    }

    public CoreTreasuryState adjustRoundingResidual(String asset, long deltaUnits) {
        return withSubledger(roundingResidualBalances, adjust(roundingResidualBalances, asset, deltaUnits, true), 2);
    }

    public CoreTreasuryState adjustClearingPnl(String asset, long deltaUnits) {
        return withSubledger(clearingPnlBalances, adjust(clearingPnlBalances, asset, deltaUnits, true), 3);
    }

    public CoreTreasuryState adjustDeficit(String asset, long deltaUnits) {
        return new CoreTreasuryState(feeBalances, insuranceBalances,
                adjust(insuranceDeficits, asset, deltaUnits, true), liquidationFeeBalances,
                fundingResidualBalances, roundingResidualBalances, clearingPnlBalances,
                fundingSettlements, lifecycleSettlements, fundingProgress, lifecycleProgress);
    }

    public CoreTreasuryState recordFunding(String symbol, long settlementId) {
        Map<String, FundingProgress> progress = StateMapSupport.delta(fundingProgress);
        progress.remove(OrderReservation.normalizeSymbol(symbol));
        return new CoreTreasuryState(feeBalances, insuranceBalances, insuranceDeficits,
                liquidationFeeBalances, fundingResidualBalances, roundingResidualBalances, clearingPnlBalances,
                recordMarker(fundingSettlements, symbol, settlementId), lifecycleSettlements, progress,
                lifecycleProgress);
    }

    public CoreTreasuryState recordLifecycle(String symbol, long settlementId) {
        return new CoreTreasuryState(feeBalances, insuranceBalances, insuranceDeficits,
                liquidationFeeBalances, fundingResidualBalances, roundingResidualBalances, clearingPnlBalances,
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
                liquidationFeeBalances, fundingResidualBalances, roundingResidualBalances, clearingPnlBalances,
                fundingSettlements, lifecycleSettlements, next, lifecycleProgress);
    }

    public CoreTreasuryState withLifecycleProgress(String symbol, LifecycleProgress progress) {
        String normalizedSymbol = OrderReservation.normalizeSymbol(symbol);
        Map<String, LifecycleProgress> next = StateMapSupport.delta(lifecycleProgress);
        if (progress == null) next.remove(normalizedSymbol);
        else next.put(normalizedSymbol, progress);
        return new CoreTreasuryState(feeBalances, insuranceBalances, insuranceDeficits,
                liquidationFeeBalances, fundingResidualBalances, roundingResidualBalances, clearingPnlBalances,
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
        assets.addAll(StateMapSupport.changedKeys(liquidationFeeBalances));
        assets.addAll(StateMapSupport.changedKeys(fundingResidualBalances));
        assets.addAll(StateMapSupport.changedKeys(roundingResidualBalances));
        assets.addAll(StateMapSupport.changedKeys(clearingPnlBalances));
        return Collections.unmodifiableSet(assets);
    }

    private CoreTreasuryState withSubledger(Map<String, Long> current, Map<String, Long> next, int ledger) {
        if (current == next) return this;
        return new CoreTreasuryState(feeBalances, insuranceBalances, insuranceDeficits,
                ledger == 0 ? next : liquidationFeeBalances,
                ledger == 1 ? next : fundingResidualBalances,
                ledger == 2 ? next : roundingResidualBalances,
                ledger == 3 ? next : clearingPnlBalances,
                fundingSettlements, lifecycleSettlements, fundingProgress, lifecycleProgress);
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
        if (StateMapSupport.isDeferred(source)) return StateMapSupport.freezeSorted(source);
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
        if (StateMapSupport.isDeferred(source)) return StateMapSupport.freezeSorted(source);
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
        if (StateMapSupport.isDeferred(source)) return StateMapSupport.freezeSorted(source);
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
        if (StateMapSupport.isDeferred(source)) return StateMapSupport.freezeSorted(source);
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
                                 int accountLaneId, long nextCursorUserId, UUID commandId,
                                 long markPriceTicks, long priceSequence) {
        public FundingProgress {
            if (settlementId <= 0 || instrumentVersion <= 0 || Math.absExact(fundingRatePpm) > 1_000_000
                    || accountLaneId < 0 || accountLaneId >= Long.SIZE
                    || nextCursorUserId < 0 || commandId == null || markPriceTicks <= 0 || priceSequence <= 0) {
                throw new IllegalArgumentException("invalid funding progress");
            }
        }
    }

    public record LifecycleProgress(long settlementId, long instrumentVersion, long settlementPriceTicks,
                                   long optionCashUnitsPerContract, boolean ordersComplete,
                                   int accountLaneId, long nextCursorOrderId,
                                   long nextCursorUserId, UUID commandId, long requiredInsuranceUnits) {
        public LifecycleProgress(long settlementId, long instrumentVersion, long settlementPriceTicks,
                                 long optionCashUnitsPerContract, boolean ordersComplete, int accountLaneId,
                                 long nextCursorOrderId, long nextCursorUserId, UUID commandId) {
            this(settlementId, instrumentVersion, settlementPriceTicks, optionCashUnitsPerContract, ordersComplete,
                    accountLaneId, nextCursorOrderId, nextCursorUserId, commandId, 0);
        }
        public LifecycleProgress(long settlementId, long instrumentVersion, long settlementPriceTicks,
                                 long optionCashUnitsPerContract, long nextCursorUserId, UUID commandId) {
            this(settlementId, instrumentVersion, settlementPriceTicks, optionCashUnitsPerContract,
                    true, 0, 0, nextCursorUserId, commandId);
        }

        public LifecycleProgress {
            if (settlementId <= 0 || instrumentVersion <= 0 || settlementPriceTicks < 0
                    || requiredInsuranceUnits < 0 || requiredInsuranceUnits > 0 && !ordersComplete
                    || optionCashUnitsPerContract < 0 || accountLaneId < 0 || accountLaneId >= Long.SIZE
                    || nextCursorOrderId < 0 || nextCursorUserId < 0
                    || (!ordersComplete && nextCursorUserId != 0) || (ordersComplete && nextCursorOrderId != 0)
                    || commandId == null) {
                throw new IllegalArgumentException("invalid lifecycle progress");
            }
        }
        public LifecycleProgress(long settlementId, long instrumentVersion, long settlementPriceTicks,
                                 long optionCashUnitsPerContract, boolean ordersComplete,
                                 long nextCursorOrderId, long nextCursorUserId, UUID commandId) {
            this(settlementId, instrumentVersion, settlementPriceTicks, optionCashUnitsPerContract,
                    ordersComplete, 0, nextCursorOrderId, nextCursorUserId, commandId);
        }
    }
}
