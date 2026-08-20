package com.surprising.aeron.service.state;

import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class TreasuryRuntime {
    private final IntLongHashMap feeBalances = new IntLongHashMap();
    private final IntLongHashMap insuranceBalances = new IntLongHashMap();
    private final IntLongHashMap insuranceDeficits = new IntLongHashMap();
    private final IntLongHashMap fundingSettlements = new IntLongHashMap();
    private final IntLongHashMap lifecycleSettlements = new IntLongHashMap();
    private final IntObjectHashMap<FundingProgressRuntime> fundingProgress = new IntObjectHashMap<>();
    private final IntObjectHashMap<LifecycleProgressRuntime> lifecycleProgress = new IntObjectHashMap<>();

    public long fee(int assetId) { return feeBalances.get(assetId); }
    public long insurance(int assetId) { return insuranceBalances.get(assetId); }
    public long insuranceDeficit(int assetId) { return insuranceDeficits.get(assetId); }
    public long fundingSettlement(int symbolId) { return fundingSettlements.get(symbolId); }
    public FundingProgressRuntime fundingProgress(int symbolId) { return fundingProgress.get(symbolId); }
    public long lifecycleSettlement(int symbolId) { return lifecycleSettlements.get(symbolId); }
    public LifecycleProgressRuntime lifecycleProgress(int symbolId) { return lifecycleProgress.get(symbolId); }

    public void setFee(int assetId, long units) {
        if (assetId < 0) throw new IllegalArgumentException("invalid treasury asset");
        if (units == 0) feeBalances.remove(assetId); else feeBalances.put(assetId, units);
    }

    public void setInsurance(int assetId, long units, long deficit) {
        if (assetId < 0 || units < 0 || deficit < 0 || units != 0 && deficit != 0) {
            throw new IllegalArgumentException("invalid insurance treasury state");
        }
        if (units == 0) insuranceBalances.remove(assetId); else insuranceBalances.put(assetId, units);
        if (deficit == 0) insuranceDeficits.remove(assetId); else insuranceDeficits.put(assetId, deficit);
    }

    public void adjustInsurance(int assetId, long deltaUnits) {
        long current = Math.subtractExact(insurance(assetId), insuranceDeficit(assetId));
        long next = Math.addExact(current, deltaUnits);
        setInsurance(assetId, Math.max(next, 0), next < 0 ? Math.negateExact(next) : 0);
    }

    public void setFundingSettlement(int symbolId, long settlementId) {
        if (symbolId < 0 || settlementId <= 0) {
            throw new IllegalArgumentException("invalid runtime funding settlement");
        }
        fundingSettlements.put(symbolId, settlementId);
        fundingProgress.remove(symbolId);
    }

    public void setFundingProgress(int symbolId, FundingProgressRuntime progress) {
        if (symbolId < 0 || progress == null) {
            throw new IllegalArgumentException("invalid runtime funding progress");
        }
        fundingProgress.put(symbolId, progress);
    }

    public void setLifecycleSettlement(int symbolId, long settlementId) {
        if (symbolId < 0 || settlementId <= 0) throw new IllegalArgumentException("invalid lifecycle settlement");
        lifecycleSettlements.put(symbolId, settlementId);
        lifecycleProgress.remove(symbolId);
    }

    public void setLifecycleProgress(int symbolId, LifecycleProgressRuntime progress) {
        if (symbolId < 0 || progress == null) throw new IllegalArgumentException("invalid lifecycle progress");
        lifecycleProgress.put(symbolId, progress);
    }

    void applyDelta(CoreTreasuryState before, CoreTreasuryState after, RuntimeIdentityRegistry identities) {
        if (before == null || after == null || identities == null) {
            throw new IllegalArgumentException("invalid treasury transition");
        }
        requireDelta("feeBalances", before.feeBalances(), after.feeBalances());
        requireDelta("insuranceBalances", before.insuranceBalances(), after.insuranceBalances());
        requireDelta("insuranceDeficits", before.insuranceDeficits(), after.insuranceDeficits());
        requireDelta("fundingSettlements", before.fundingSettlements(), after.fundingSettlements());
        requireDelta("lifecycleSettlements", before.lifecycleSettlements(), after.lifecycleSettlements());
        requireDelta("fundingProgress", before.fundingProgress(), after.fundingProgress());
        requireDelta("lifecycleProgress", before.lifecycleProgress(), after.lifecycleProgress());

        syncFeeBalances(before.feeBalances(), after.feeBalances(), identities);
        syncInsurance(before, after, identities);
        syncFunding(before, after, identities);
        syncLifecycle(before, after, identities);
    }

    private void syncFeeBalances(Map<String, Long> before, Map<String, Long> after,
                                 RuntimeIdentityRegistry identities) {
        for (String asset : StateMapSupport.changedKeys(before, after)) {
            Long units = after.get(asset);
            setFee(identities.assetId(asset), units == null ? 0 : units);
        }
    }

    private void syncInsurance(CoreTreasuryState before, CoreTreasuryState after,
                               RuntimeIdentityRegistry identities) {
        Set<String> changedAssets = new TreeSet<>(StateMapSupport.changedKeys(
                before.insuranceBalances(), after.insuranceBalances()));
        changedAssets.addAll(StateMapSupport.changedKeys(before.insuranceDeficits(), after.insuranceDeficits()));
        for (String asset : changedAssets) {
            setInsurance(identities.assetId(asset),
                    after.insuranceBalances().getOrDefault(asset, 0L),
                    after.insuranceDeficits().getOrDefault(asset, 0L));
        }
    }

    private void syncFunding(CoreTreasuryState before, CoreTreasuryState after,
                             RuntimeIdentityRegistry identities) {
        Set<String> changedSymbols = new TreeSet<>(StateMapSupport.changedKeys(
                before.fundingSettlements(), after.fundingSettlements()));
        changedSymbols.addAll(StateMapSupport.changedKeys(before.fundingProgress(), after.fundingProgress()));
        for (String symbol : changedSymbols) {
            Long settlementId = after.fundingSettlements().get(symbol);
            int symbolId = identities.symbolId(symbol);
            if (settlementId == null) removeFundingSettlement(symbolId);
            else setFundingSettlement(symbolId, settlementId);
            CoreTreasuryState.FundingProgress progress = after.fundingProgress().get(symbol);
            if (progress == null) removeFundingProgress(symbolId);
            else setFundingProgress(symbolId, new FundingProgressRuntime(progress.settlementId(),
                    progress.instrumentVersion(), progress.fundingRatePpm(), progress.nextCursorUserId(),
                    progress.commandId()));
        }
    }

    private void syncLifecycle(CoreTreasuryState before, CoreTreasuryState after,
                               RuntimeIdentityRegistry identities) {
        Set<String> changedSymbols = new TreeSet<>(StateMapSupport.changedKeys(
                before.lifecycleSettlements(), after.lifecycleSettlements()));
        changedSymbols.addAll(StateMapSupport.changedKeys(before.lifecycleProgress(), after.lifecycleProgress()));
        for (String symbol : changedSymbols) {
            Long settlementId = after.lifecycleSettlements().get(symbol);
            int symbolId = identities.symbolId(symbol);
            if (settlementId == null) removeLifecycleSettlement(symbolId);
            else setLifecycleSettlement(symbolId, settlementId);
            CoreTreasuryState.LifecycleProgress progress = after.lifecycleProgress().get(symbol);
            if (progress == null) removeLifecycleProgress(symbolId);
            else setLifecycleProgress(symbolId, new LifecycleProgressRuntime(progress.settlementId(),
                    progress.instrumentVersion(), progress.settlementPriceTicks(), progress.optionCashUnitsPerContract(),
                    progress.ordersComplete(), progress.nextCursorOrderId(), progress.nextCursorUserId(),
                    progress.commandId()));
        }
    }

    private void removeFundingSettlement(int symbolId) {
        fundingSettlements.remove(symbolId);
    }

    private void removeFundingProgress(int symbolId) {
        fundingProgress.remove(symbolId);
    }

    private void removeLifecycleSettlement(int symbolId) {
        lifecycleSettlements.remove(symbolId);
    }

    private void removeLifecycleProgress(int symbolId) {
        lifecycleProgress.remove(symbolId);
    }

    private static void requireDelta(String field, Map<?, ?> before, Map<?, ?> after) {
        if (!StateMapSupport.isDeltaDescendantOf(before, after)) {
            throw new IllegalStateException("online treasury transition is not a delta: " + field);
        }
    }

    public void clear() {
        feeBalances.clear();
        insuranceBalances.clear();
        insuranceDeficits.clear();
        fundingSettlements.clear();
        lifecycleSettlements.clear();
        fundingProgress.clear();
        lifecycleProgress.clear();
    }

    public IntLongHashMap feeBalances() { return new IntLongHashMap(feeBalances); }
    public IntLongHashMap insuranceBalances() { return new IntLongHashMap(insuranceBalances); }
    public IntLongHashMap insuranceDeficits() { return new IntLongHashMap(insuranceDeficits); }
    public IntLongHashMap fundingSettlements() { return new IntLongHashMap(fundingSettlements); }
    public IntObjectHashMap<FundingProgressRuntime> fundingProgresses() {
        return new IntObjectHashMap<>(fundingProgress);
    }
    public IntLongHashMap lifecycleSettlements() { return new IntLongHashMap(lifecycleSettlements); }
    public IntObjectHashMap<LifecycleProgressRuntime> lifecycleProgresses() {
        return new IntObjectHashMap<>(lifecycleProgress);
    }

    public record FundingProgressRuntime(long settlementId, long instrumentVersion, long fundingRatePpm,
                                         long nextCursorUserId, UUID commandId) {
        public FundingProgressRuntime {
            if (settlementId <= 0 || instrumentVersion <= 0 || Math.absExact(fundingRatePpm) > 1_000_000
                    || nextCursorUserId < 0 || commandId == null) {
                throw new IllegalArgumentException("invalid runtime funding progress");
            }
        }
    }

    public record LifecycleProgressRuntime(long settlementId, long instrumentVersion, long settlementPriceTicks,
                                           long optionCashUnitsPerContract, boolean ordersComplete,
                                           long nextCursorOrderId, long nextCursorUserId, UUID commandId) {
        public LifecycleProgressRuntime {
            if (settlementId <= 0 || instrumentVersion <= 0 || settlementPriceTicks < 0
                    || optionCashUnitsPerContract < 0 || nextCursorOrderId < 0 || nextCursorUserId < 0
                    || (!ordersComplete && nextCursorUserId != 0) || (ordersComplete && nextCursorOrderId != 0)
                    || commandId == null) throw new IllegalArgumentException("invalid lifecycle progress");
        }
    }
}
