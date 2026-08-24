package com.surprising.aeron.service.state;

import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.IntHashSet;

import java.util.UUID;

public final class TreasuryRuntime {
    private final IntLongHashMap feeBalances = new IntLongHashMap();
    private final IntLongHashMap insuranceBalances = new IntLongHashMap();
    private final IntLongHashMap insuranceDeficits = new IntLongHashMap();
    private final IntLongHashMap liquidationFeeBalances = new IntLongHashMap();
    private final IntLongHashMap fundingResidualBalances = new IntLongHashMap();
    private final IntLongHashMap roundingResidualBalances = new IntLongHashMap();
    private final IntLongHashMap clearingPnlBalances = new IntLongHashMap();
    private final IntLongHashMap fundingSettlements = new IntLongHashMap();
    private final IntLongHashMap lifecycleSettlements = new IntLongHashMap();
    private final IntObjectHashMap<FundingProgressRuntime> fundingProgress = new IntObjectHashMap<>();
    private final IntObjectHashMap<LifecycleProgressRuntime> lifecycleProgress = new IntObjectHashMap<>();
    private final IntHashSet changedAssets = new IntHashSet();
    private final IntHashSet changedFundingSymbols = new IntHashSet();
    private final IntHashSet changedLifecycleSymbols = new IntHashSet();
    private Thread owner;

    void assertOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) owner = current;
        else if (owner != current) throw new IllegalStateException("treasury runtime is bound to another thread");
    }

    void releaseOwnerForHandoff() {
        owner = null;
    }

    public long fee(int assetId) { assertOwner(); return feeBalances.get(assetId); }
    public long insurance(int assetId) { assertOwner(); return insuranceBalances.get(assetId); }
    public long insuranceDeficit(int assetId) { assertOwner(); return insuranceDeficits.get(assetId); }
    public long deficit(int assetId) { assertOwner(); return insuranceDeficits.get(assetId); }
    public long liquidationFee(int assetId) { assertOwner(); return liquidationFeeBalances.get(assetId); }
    public long fundingResidual(int assetId) { assertOwner(); return fundingResidualBalances.get(assetId); }
    public long roundingResidual(int assetId) { assertOwner(); return roundingResidualBalances.get(assetId); }
    public long clearingPnl(int assetId) { assertOwner(); return clearingPnlBalances.get(assetId); }
    public long fundingSettlement(int symbolId) { assertOwner(); return fundingSettlements.get(symbolId); }
    public FundingProgressRuntime fundingProgress(int symbolId) { assertOwner(); return fundingProgress.get(symbolId); }
    public long lifecycleSettlement(int symbolId) { assertOwner(); return lifecycleSettlements.get(symbolId); }
    public LifecycleProgressRuntime lifecycleProgress(int symbolId) { assertOwner(); return lifecycleProgress.get(symbolId); }

    public void setFee(int assetId, long units) {
        assertOwner();
        setSigned(feeBalances, assetId, units);
        changedAssets.add(assetId);
    }

    public void setInsurance(int assetId, long units, long deficit) {
        assertOwner();
        setSigned(insuranceBalances, assetId, units);
        setSigned(insuranceDeficits, assetId, deficit);
        changedAssets.add(assetId);
    }

    public void adjustInsurance(int assetId, long deltaUnits) {
        assertOwner();
        setSigned(insuranceBalances, assetId, Math.addExact(insurance(assetId), deltaUnits));
        changedAssets.add(assetId);
    }

    public void setLiquidationFee(int assetId, long units) {
        assertOwner();
        setSigned(liquidationFeeBalances, assetId, units);
        changedAssets.add(assetId);
    }

    public void setFundingResidual(int assetId, long units) {
        assertOwner();
        setSigned(fundingResidualBalances, assetId, units);
        changedAssets.add(assetId);
    }

    public void setRoundingResidual(int assetId, long units) {
        assertOwner();
        setSigned(roundingResidualBalances, assetId, units);
        changedAssets.add(assetId);
    }

    public void setClearingPnl(int assetId, long units) {
        assertOwner();
        setSigned(clearingPnlBalances, assetId, units);
        changedAssets.add(assetId);
    }

    public void setDeficit(int assetId, long units) {
        assertOwner();
        setSigned(insuranceDeficits, assetId, units);
        changedAssets.add(assetId);
    }

    public void setFundingSettlement(int symbolId, long settlementId) {
        assertOwner();
        if (symbolId < 0 || settlementId <= 0) {
            throw new IllegalArgumentException("invalid runtime funding settlement");
        }
        fundingSettlements.put(symbolId, settlementId);
        fundingProgress.remove(symbolId);
        changedFundingSymbols.add(symbolId);
    }

    public void setFundingProgress(int symbolId, FundingProgressRuntime progress) {
        assertOwner();
        if (symbolId < 0 || progress == null) {
            throw new IllegalArgumentException("invalid runtime funding progress");
        }
        fundingProgress.put(symbolId, progress);
        changedFundingSymbols.add(symbolId);
    }

    public void setLifecycleSettlement(int symbolId, long settlementId) {
        assertOwner();
        if (symbolId < 0 || settlementId <= 0) throw new IllegalArgumentException("invalid lifecycle settlement");
        lifecycleSettlements.put(symbolId, settlementId);
        lifecycleProgress.remove(symbolId);
        changedLifecycleSymbols.add(symbolId);
    }

    public void setLifecycleProgress(int symbolId, LifecycleProgressRuntime progress) {
        assertOwner();
        if (symbolId < 0 || progress == null) throw new IllegalArgumentException("invalid lifecycle progress");
        lifecycleProgress.put(symbolId, progress);
        changedLifecycleSymbols.add(symbolId);
    }

    IntHashSet changedAssets() {
        assertOwner();
        return new IntHashSet(changedAssets);
    }

    IntHashSet changedFundingSymbols() {
        assertOwner();
        return new IntHashSet(changedFundingSymbols);
    }

    IntHashSet changedLifecycleSymbols() {
        assertOwner();
        return new IntHashSet(changedLifecycleSymbols);
    }

    void clearChangedKeys() {
        assertOwner();
        changedAssets.clear();
        changedFundingSymbols.clear();
        changedLifecycleSymbols.clear();
    }

    public void clear() {
        assertOwner();
        feeBalances.clear();
        insuranceBalances.clear();
        insuranceDeficits.clear();
        liquidationFeeBalances.clear();
        fundingResidualBalances.clear();
        roundingResidualBalances.clear();
        clearingPnlBalances.clear();
        fundingSettlements.clear();
        lifecycleSettlements.clear();
        fundingProgress.clear();
        lifecycleProgress.clear();
    }

    public IntLongHashMap feeBalances() { assertOwner(); return new IntLongHashMap(feeBalances); }
    public IntLongHashMap insuranceBalances() { assertOwner(); return new IntLongHashMap(insuranceBalances); }
    public IntLongHashMap insuranceDeficits() { assertOwner(); return new IntLongHashMap(insuranceDeficits); }
    public IntLongHashMap deficitBalances() { assertOwner(); return new IntLongHashMap(insuranceDeficits); }
    public IntLongHashMap liquidationFeeBalances() { assertOwner(); return new IntLongHashMap(liquidationFeeBalances); }
    public IntLongHashMap fundingResidualBalances() { assertOwner(); return new IntLongHashMap(fundingResidualBalances); }
    public IntLongHashMap roundingResidualBalances() { assertOwner(); return new IntLongHashMap(roundingResidualBalances); }
    public IntLongHashMap clearingPnlBalances() { assertOwner(); return new IntLongHashMap(clearingPnlBalances); }
    public IntLongHashMap fundingSettlements() { assertOwner(); return new IntLongHashMap(fundingSettlements); }
    public IntObjectHashMap<FundingProgressRuntime> fundingProgresses() {
        assertOwner();
        return new IntObjectHashMap<>(fundingProgress);
    }
    public IntLongHashMap lifecycleSettlements() { assertOwner(); return new IntLongHashMap(lifecycleSettlements); }
    public IntObjectHashMap<LifecycleProgressRuntime> lifecycleProgresses() {
        assertOwner();
        return new IntObjectHashMap<>(lifecycleProgress);
    }

    private static void setSigned(IntLongHashMap balances, int assetId, long units) {
        if (assetId < 0) throw new IllegalArgumentException("invalid treasury asset");
        if (units == 0) balances.remove(assetId); else balances.put(assetId, units);
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
