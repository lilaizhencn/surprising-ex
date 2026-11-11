package com.surprising.aeron.service.treasury;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.model.InstrumentId;
import com.surprising.aeron.service.order.OrderFundsReservation.Snapshot;
import com.surprising.aeron.support.collection.StateMapSupport;
import java.util.Map;
import java.util.TreeMap;

/** 快照与恢复使用的系统账本值。 */
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
        feeBalances = normalized(feeBalances);
        insuranceBalances = normalized(insuranceBalances);
        insuranceDeficits = normalized(insuranceDeficits);
        liquidationFeeBalances = normalized(liquidationFeeBalances);
        fundingResidualBalances = normalized(fundingResidualBalances);
        roundingResidualBalances = normalized(roundingResidualBalances);
        clearingPnlBalances = normalized(clearingPnlBalances);
        fundingSettlements = markers(fundingSettlements);
        lifecycleSettlements = markers(lifecycleSettlements);
        fundingProgress = progresses(fundingProgress);
        lifecycleProgress = lifecycleProgresses(lifecycleProgress);
    }

    /** 创建七类资金、完成编号和进度均为空的冷账本值。 */
    public static CoreTreasuryState empty() {
        return new CoreTreasuryState(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static Map<String, Long> normalized(Map<String, Long> source) {
        if (StateMapSupport.isFrozen(source)) return source;
        Map<String, Long> result = new TreeMap<>();
        source.forEach((asset, units) -> {
            String normalizedAsset = AssetBalance.normalizeAsset(asset);
            if (units == null) {
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
        Map<String, Long> result = new TreeMap<>();
        source.forEach((instrumentId, settlementId) -> {
            String normalizedSymbol = InstrumentId.requireValid(instrumentId);
            if (settlementId == null || settlementId <= 0 || result.put(normalizedSymbol, settlementId) != null) {
                throw new IllegalArgumentException("invalid settlement marker");
            }
        });
        return StateMapSupport.freezeSorted(result);
    }

    private static Map<String, FundingProgress> progresses(Map<String, FundingProgress> source) {
        if (StateMapSupport.isFrozen(source)) return source;
        Map<String, FundingProgress> result = new TreeMap<>();
        source.forEach((instrumentId, progress) -> {
            String normalizedSymbol = InstrumentId.requireValid(instrumentId);
            validateProgress(normalizedSymbol, progress);
            if (result.put(normalizedSymbol, progress) != null) {
                throw new IllegalArgumentException("duplicate funding progress");
            }
        });
        return StateMapSupport.freezeSorted(result);
    }

    private static Map<String, LifecycleProgress> lifecycleProgresses(Map<String, LifecycleProgress> source) {
        if (StateMapSupport.isFrozen(source)) return source;
        Map<String, LifecycleProgress> result = new TreeMap<>();
        source.forEach((instrumentId, progress) -> {
            String normalizedSymbol = InstrumentId.requireValid(instrumentId);
            validateLifecycleProgress(normalizedSymbol, progress);
            if (result.put(normalizedSymbol, progress) != null) {
                throw new IllegalArgumentException("duplicate lifecycle progress");
            }
        });
        return StateMapSupport.freezeSorted(result);
    }

    private static void validateProgress(String instrumentId, FundingProgress progress) {
        if (!InstrumentId.requireValid(instrumentId).equals(instrumentId) || progress == null) {
            throw new IllegalArgumentException("invalid funding progress");
        }
    }

    private static void validateLifecycleProgress(String instrumentId, LifecycleProgress progress) {
        if (!InstrumentId.requireValid(instrumentId).equals(instrumentId) || progress == null) {
            throw new IllegalArgumentException("invalid lifecycle progress");
        }
    }

    /** 资金费续页的固定费率、用户游标和计算价格。 */
    public record FundingProgress(long settlementId, long fundingRatePpm,
            long nextCursorUserId, long markPriceTicks) {
        public FundingProgress {
            if (settlementId <= 0 || Math.absExact(fundingRatePpm) > 1_000_000
                    || nextCursorUserId < 0 || markPriceTicks <= 0) {
                throw new IllegalArgumentException("invalid funding progress");
            }
        }
    }

    /** 到期结算的固定价格、撤单/用户游标及本页保险需求。 */
    public record LifecycleProgress(long settlementId, long settlementPriceTicks,
            boolean ordersComplete, long nextCursorOrderId, long nextCursorUserId,
            long requiredInsuranceUnits) {
        public LifecycleProgress {
            if (settlementId <= 0 || settlementPriceTicks < 0
                    || requiredInsuranceUnits < 0 || requiredInsuranceUnits > 0 && !ordersComplete
                    || nextCursorOrderId < 0 || nextCursorUserId < 0
                    || (!ordersComplete && nextCursorUserId != 0) || (ordersComplete && nextCursorOrderId != 0)) {
                throw new IllegalArgumentException("invalid lifecycle progress");
            }
        }
    }
}
