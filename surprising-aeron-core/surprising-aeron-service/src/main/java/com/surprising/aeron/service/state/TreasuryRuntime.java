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
    private final IntObjectHashMap<RuntimeFactFrame.TreasuryAssetValue> patchAssetBefore =
            new IntObjectHashMap<>();
    private final IntObjectHashMap<RuntimeFactFrame.TreasuryFundingValue> patchFundingBefore =
            new IntObjectHashMap<>();
    private final IntObjectHashMap<RuntimeFactFrame.TreasuryLifecycleValue> patchLifecycleBefore =
            new IntObjectHashMap<>();
    private Thread owner;
    private boolean orderBatchMutationScope;

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

    void beginOrderBatchMutationScope() {
        assertOwner();
        if (orderBatchMutationScope) throw new IllegalStateException("Treasury order batch scope is already active");
        orderBatchMutationScope = true;
    }

    void endOrderBatchMutationScope() {
        assertOwner();
        orderBatchMutationScope = false;
    }

    private void rejectNonAssetOrderBatchMutation(String domain) {
        if (orderBatchMutationScope) throw new IllegalStateException("order batch cannot mutate " + domain);
    }

    public int assetLedgerEntryCount() {
        assertOwner();
        return Math.addExact(Math.addExact(Math.addExact(feeBalances.size(), insuranceBalances.size()),
                        Math.addExact(insuranceDeficits.size(), liquidationFeeBalances.size())),
                Math.addExact(Math.addExact(fundingResidualBalances.size(), roundingResidualBalances.size()),
                        clearingPnlBalances.size()));
    }

    public void setFee(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(feeBalances, assetId, units);
        changedAssets.add(assetId);
    }

    public void setInsurance(int assetId, long units, long deficit) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(insuranceBalances, assetId, units);
        setSigned(insuranceDeficits, assetId, deficit);
        changedAssets.add(assetId);
    }

    public void adjustInsurance(int assetId, long deltaUnits) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(insuranceBalances, assetId, Math.addExact(insurance(assetId), deltaUnits));
        changedAssets.add(assetId);
    }

    public void setLiquidationFee(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(liquidationFeeBalances, assetId, units);
        changedAssets.add(assetId);
    }

    public void setFundingResidual(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(fundingResidualBalances, assetId, units);
        changedAssets.add(assetId);
    }

    public void setRoundingResidual(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(roundingResidualBalances, assetId, units);
        changedAssets.add(assetId);
    }

    public void setClearingPnl(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(clearingPnlBalances, assetId, units);
        changedAssets.add(assetId);
    }

    public void setDeficit(int assetId, long units) {
        assertOwner();
        captureAssetBefore(assetId);
        setSigned(insuranceDeficits, assetId, units);
        changedAssets.add(assetId);
    }

    public void setFundingSettlement(int symbolId, long settlementId) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury funding state");
        if (symbolId < 0 || settlementId <= 0) {
            throw new IllegalArgumentException("invalid runtime funding settlement");
        }
        captureFundingBefore(symbolId);
        fundingSettlements.put(symbolId, settlementId);
        fundingProgress.remove(symbolId);
        changedFundingSymbols.add(symbolId);
    }

    public void setFundingProgress(int symbolId, FundingProgressRuntime progress) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury funding state");
        if (symbolId < 0 || progress == null) {
            throw new IllegalArgumentException("invalid runtime funding progress");
        }
        captureFundingBefore(symbolId);
        fundingProgress.put(symbolId, progress);
        changedFundingSymbols.add(symbolId);
    }

    public void setLifecycleSettlement(int symbolId, long settlementId) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury lifecycle state");
        if (symbolId < 0 || settlementId <= 0) throw new IllegalArgumentException("invalid lifecycle settlement");
        captureLifecycleBefore(symbolId);
        lifecycleSettlements.put(symbolId, settlementId);
        lifecycleProgress.remove(symbolId);
        changedLifecycleSymbols.add(symbolId);
    }

    public void setLifecycleProgress(int symbolId, LifecycleProgressRuntime progress) {
        assertOwner();
        rejectNonAssetOrderBatchMutation("Treasury lifecycle state");
        if (symbolId < 0 || progress == null) throw new IllegalArgumentException("invalid lifecycle progress");
        captureLifecycleBefore(symbolId);
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
        patchAssetBefore.clear();
        patchFundingBefore.clear();
        patchLifecycleBefore.clear();
    }

    RuntimeFactFrame.TreasuryAssetValue patchAssetBefore(int assetId) {
        assertOwner();
        return patchAssetBefore.get(assetId);
    }

    RuntimeFactFrame.TreasuryFundingValue patchFundingBefore(int symbolId) {
        assertOwner();
        return patchFundingBefore.get(symbolId);
    }

    RuntimeFactFrame.TreasuryLifecycleValue patchLifecycleBefore(int symbolId) {
        assertOwner();
        return patchLifecycleBefore.get(symbolId);
    }

    void rollbackChangedValues() {
        assertOwner();
        for (int assetId : changedAssets.toArray()) {
            RuntimeFactFrame.TreasuryAssetValue before = patchAssetBefore.get(assetId);
            restoreSigned(feeBalances, assetId, before == null ? 0 : before.fee());
            restoreSigned(insuranceBalances, assetId, before == null ? 0 : before.insurance());
            restoreSigned(insuranceDeficits, assetId, before == null ? 0 : before.deficit());
            restoreSigned(liquidationFeeBalances, assetId, before == null ? 0 : before.liquidationFee());
            restoreSigned(fundingResidualBalances, assetId, before == null ? 0 : before.fundingResidual());
            restoreSigned(roundingResidualBalances, assetId, before == null ? 0 : before.roundingResidual());
            restoreSigned(clearingPnlBalances, assetId, before == null ? 0 : before.clearingPnl());
        }
        for (int symbolId : changedFundingSymbols.toArray()) {
            RuntimeFactFrame.TreasuryFundingValue before = patchFundingBefore.get(symbolId);
            restoreSigned(fundingSettlements, symbolId, before == null ? 0 : before.settlementId());
            if (before == null || before.progress() == null) fundingProgress.remove(symbolId);
            else fundingProgress.put(symbolId, before.progress());
        }
        for (int symbolId : changedLifecycleSymbols.toArray()) {
            RuntimeFactFrame.TreasuryLifecycleValue before = patchLifecycleBefore.get(symbolId);
            restoreSigned(lifecycleSettlements, symbolId, before == null ? 0 : before.settlementId());
            if (before == null || before.progress() == null) lifecycleProgress.remove(symbolId);
            else lifecycleProgress.put(symbolId, before.progress());
        }
        clearChangedKeys();
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

    public int incompleteFundingCount() {
        assertOwner();
        return fundingProgress.size();
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

    private static void restoreSigned(IntLongHashMap values, int key, long value) {
        if (value == 0) values.remove(key); else values.put(key, value);
    }

    private void captureAssetBefore(int assetId) {
        if (changedAssets.contains(assetId)) return;
        RuntimeFactFrame.TreasuryAssetValue value = currentAsset(assetId);
        if (value != null) patchAssetBefore.put(assetId, value);
    }

    private RuntimeFactFrame.TreasuryAssetValue currentAsset(int assetId) {
        long fee = feeBalances.get(assetId);
        long insurance = insuranceBalances.get(assetId);
        long deficit = insuranceDeficits.get(assetId);
        long liquidationFee = liquidationFeeBalances.get(assetId);
        long fundingResidual = fundingResidualBalances.get(assetId);
        long roundingResidual = roundingResidualBalances.get(assetId);
        long clearingPnl = clearingPnlBalances.get(assetId);
        if ((fee | insurance | deficit | liquidationFee | fundingResidual | roundingResidual | clearingPnl) == 0) {
            return null;
        }
        return new RuntimeFactFrame.TreasuryAssetValue(fee, insurance, deficit, liquidationFee,
                fundingResidual, roundingResidual, clearingPnl);
    }

    private void captureFundingBefore(int symbolId) {
        if (changedFundingSymbols.contains(symbolId)) return;
        long settlementId = fundingSettlements.get(symbolId);
        FundingProgressRuntime progress = fundingProgress.get(symbolId);
        if (settlementId != 0 || progress != null) {
            patchFundingBefore.put(symbolId,
                    new RuntimeFactFrame.TreasuryFundingValue(settlementId, progress));
        }
    }

    private void captureLifecycleBefore(int symbolId) {
        if (changedLifecycleSymbols.contains(symbolId)) return;
        long settlementId = lifecycleSettlements.get(symbolId);
        LifecycleProgressRuntime progress = lifecycleProgress.get(symbolId);
        if (settlementId != 0 || progress != null) {
            patchLifecycleBefore.put(symbolId,
                    new RuntimeFactFrame.TreasuryLifecycleValue(settlementId, progress));
        }
    }

    public record FundingProgressRuntime(long settlementId, long instrumentVersion, long fundingRatePpm,
                                         int accountLaneId, long nextCursorUserId, UUID commandId,
                                         long markPriceTicks, long priceSequence) {
        public FundingProgressRuntime {
            if (settlementId <= 0 || instrumentVersion <= 0 || Math.absExact(fundingRatePpm) > 1_000_000
                    || accountLaneId < 0 || accountLaneId >= Long.SIZE
                    || nextCursorUserId < 0 || commandId == null || markPriceTicks <= 0 || priceSequence <= 0) {
                throw new IllegalArgumentException("invalid runtime funding progress");
            }
        }
    }

    public record LifecycleProgressRuntime(long settlementId, long instrumentVersion, long settlementPriceTicks,
                                           long optionCashUnitsPerContract, boolean ordersComplete,
                                           int accountLaneId, long nextCursorOrderId,
                                           long nextCursorUserId, UUID commandId) {
        public LifecycleProgressRuntime {
            if (settlementId <= 0 || instrumentVersion <= 0 || settlementPriceTicks < 0
                    || optionCashUnitsPerContract < 0 || accountLaneId < 0 || accountLaneId >= Long.SIZE
                    || nextCursorOrderId < 0 || nextCursorUserId < 0
                    || (!ordersComplete && nextCursorUserId != 0) || (ordersComplete && nextCursorOrderId != 0)
                    || commandId == null) throw new IllegalArgumentException("invalid lifecycle progress");
        }
        public LifecycleProgressRuntime(long settlementId, long instrumentVersion, long settlementPriceTicks,
                                        long optionCashUnitsPerContract, boolean ordersComplete,
                                        long nextCursorOrderId, long nextCursorUserId, UUID commandId) {
            this(settlementId, instrumentVersion, settlementPriceTicks, optionCashUnitsPerContract,
                    ordersComplete, 0, nextCursorOrderId, nextCursorUserId, commandId);
        }
    }
}
