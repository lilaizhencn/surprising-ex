package com.surprising.aeron.service.state;

import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;

import java.util.UUID;

public final class TreasuryRuntime {
    private final IntLongHashMap feeBalances = new IntLongHashMap();
    private final IntLongHashMap insuranceBalances = new IntLongHashMap();
    private final IntLongHashMap insuranceDeficits = new IntLongHashMap();
    private final IntLongHashMap fundingSettlements = new IntLongHashMap();
    private final IntObjectHashMap<FundingProgressRuntime> fundingProgress = new IntObjectHashMap<>();

    public long fee(int assetId) { return feeBalances.get(assetId); }
    public long insurance(int assetId) { return insuranceBalances.get(assetId); }
    public long insuranceDeficit(int assetId) { return insuranceDeficits.get(assetId); }
    public long fundingSettlement(int symbolId) { return fundingSettlements.get(symbolId); }
    public FundingProgressRuntime fundingProgress(int symbolId) { return fundingProgress.get(symbolId); }

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

    public IntLongHashMap feeBalances() { return new IntLongHashMap(feeBalances); }
    public IntLongHashMap insuranceBalances() { return new IntLongHashMap(insuranceBalances); }
    public IntLongHashMap insuranceDeficits() { return new IntLongHashMap(insuranceDeficits); }
    public IntLongHashMap fundingSettlements() { return new IntLongHashMap(fundingSettlements); }
    public IntObjectHashMap<FundingProgressRuntime> fundingProgresses() {
        return new IntObjectHashMap<>(fundingProgress);
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
}
