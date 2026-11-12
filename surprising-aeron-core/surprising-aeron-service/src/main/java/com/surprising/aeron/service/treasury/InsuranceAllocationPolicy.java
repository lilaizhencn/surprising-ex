package com.surprising.aeron.service.treasury;

import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.service.core.AccountBook;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.risk.RiskScanner;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

/** 按结算资产共享保险预算的确定性比例分配。先按触发价格序号、用户、合约、持仓方向及强平编号排序，以大整数计算各缺口比例，整除后的余数按该固定顺序逐项补一。候选唯一性及跨线程引用生命周期由调用方保证。构造约定：只提供保险索赔分组和分配静态入口，不允许单独实例化。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class InsuranceAllocationPolicy {

    /** 索赔固定顺序：触发价格序号、用户、合约名、持仓方向、强平编号。 */
    private static final Comparator<RiskScanner.LiquidationOrder> CLAIM_ORDER = Comparator
            .comparingLong(RiskScanner.LiquidationOrder::triggerPriceSequence)
            .thenComparingLong(RiskScanner.LiquidationOrder::userId)
            .thenComparing(value -> value.instrument().instrumentId())
            .thenComparingInt(value -> value.positionSide().ordinal())
            .thenComparingLong(RiskScanner.LiquidationOrder::liquidationId);

    /** 当前命令或查询持有的按资产索赔引用分组，预算由账本读取。列表不是永久不可变副本，后续分配会原地排序，生命周期由调用方交接。 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class AllocationInputs {
        private final Map<Integer, ArrayList<RiskScanner.LiquidationOrder>> byAsset;
    }

    /** 在已有账户 Lane 命令作用域读取对应强平记录，按上游配好的资产数组分组，跳过缺失、非保险阶段或币对对象不符项。 */
    public static AllocationInputs prepareLaneAllocations(AccountBook accountBook, long[] ids,
            int[] assetIds, CoreInstrument[] instruments) {
        if (accountBook.currentLaneCommandScope() == null) throw new IllegalStateException("insurance claim read requires Lane ownership");
        Map<Integer, ArrayList<RiskScanner.LiquidationOrder>> byAsset = new HashMap<>();
        for (int index = 0; index < ids.length; index++) {
            RiskScanner.LiquidationOrder value = accountBook.liquidation(ids[index]);
            CoreInstrument instrument = instruments[index];
            if (value == null || value.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED
                    || instrument == null || instrument != value.instrument()) continue;
            byAsset.computeIfAbsent(assetIds[index], ignored -> new ArrayList<>()).add(value);
        }
        return new AllocationInputs(byAsset);
    }

    /** 把各 Lane 已交接的索赔列表按资产拼到新输入容器，跳过空 Lane 输入，不复制索赔记录或重新去重。 */
    public static Map<Long, Long> allocations(SystemFunds ledger, AllocationInputs[] laneClaims) {
        Map<Integer, ArrayList<RiskScanner.LiquidationOrder>> merged = new HashMap<>();
        for (AllocationInputs inputs : laneClaims) if (inputs != null)
            // 当前资产需要分配的独占索赔列表，排序会改变列表顺序。
            inputs.byAsset.forEach((assetId, claims) -> merged.computeIfAbsent(assetId, ignored -> new ArrayList<>()).addAll(claims));
        Map<Long, Long> result = new HashMap<>();
        merged.forEach((assetId, claims) -> allocateAsset(ledger.insurance(assetId), claims, result));
        return result;
    }

    /** 在账本中从 Lane 回执有效前缀收集索赔，按固定顺序计算目标份额。 */
    public static long resolve(RiskScanner.LiquidationOrder[] receipts, int count, long liquidationId,
                                     int assetId, SystemFunds ledger) {
        ArrayList<RiskScanner.LiquidationOrder> claims = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            RiskScanner.LiquidationOrder value = receipts[index];
            if (value != null) claims.add(value);
        }
        if (claims.isEmpty()) return -1;
        claims.sort(CLAIM_ORDER);
        int targetIndex = -1;
        BigInteger total = BigInteger.ZERO;
        for (int index = 0; index < claims.size(); index++) {
            RiskScanner.LiquidationOrder claim = claims.get(index);
            total = total.add(BigInteger.valueOf(claim.deficitUnits()));
            if (claim.liquidationId() == liquidationId) targetIndex = index;
        }
        if (targetIndex != 0 || total.signum() == 0) return -1;
        BigInteger available = BigInteger.valueOf(Math.max(0, ledger.insurance(assetId))).min(total);
        if (available.signum() == 0) return 0;
        if (available.equals(total)) return claims.get(0).deficitUnits();
        BigInteger allocated = BigInteger.ZERO;
        long targetBase = 0;
        for (RiskScanner.LiquidationOrder claim : claims) {
            BigInteger share = available.multiply(BigInteger.valueOf(claim.deficitUnits()))
                    .divide(total);
            allocated = allocated.add(share);
            if (claim.liquidationId() == liquidationId) targetBase = share.longValueExact();
        }
        int remainder = available.subtract(allocated).intValueExact();
        return targetBase + (remainder > 0 ? 1 : 0);
    }

    /** 对单资产索赔原地排序，以非负预算和总缺口取小，按大整数比例整除并把剩余单位按排序前缀补一，写入每个强平编号的结果。 */
    private static void allocateAsset(long availableUnits, ArrayList<RiskScanner.LiquidationOrder> claims, Map<Long, Long> result) {
        claims.sort(CLAIM_ORDER);
        BigInteger total = BigInteger.ZERO;
        for (RiskScanner.LiquidationOrder claim : claims) total = total.add(BigInteger.valueOf(claim.deficitUnits()));
        BigInteger available = BigInteger.valueOf(Math.max(0, availableUnits)).min(total);
        if (available.signum() == 0) {
            for (RiskScanner.LiquidationOrder claim : claims) result.put(claim.liquidationId(), 0L);
            return;
        }
        long[] base = new long[claims.size()];
        BigInteger allocated = BigInteger.ZERO;
        for (int index = 0; index < claims.size(); index++) {
            BigInteger share = available.multiply(BigInteger.valueOf(claims.get(index).deficitUnits()))
                    .divide(total);
            base[index] = share.longValueExact();
            allocated = allocated.add(share);
        }
        int remainder = available.subtract(allocated).intValueExact();
        for (int index = 0; index < claims.size(); index++) {
            long coverage = base[index] + (index < remainder ? 1 : 0);
            result.put(claims.get(index).liquidationId(), coverage);
        }
    }

}
