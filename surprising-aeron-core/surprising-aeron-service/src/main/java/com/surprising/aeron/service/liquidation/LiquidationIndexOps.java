package com.surprising.aeron.service.liquidation;

import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.risk.RiskScanner;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/**
 * 强平状态在 AccountPartition 中的活动索引维护操作。
 */
public final class LiquidationIndexOps {

    private LiquidationIndexOps() {}

    /**
     * 判断强平是否尚未 COMPLETED 且未 CANCELED，用作活动索引的统一分类。
     */
    public static boolean active(RiskScanner.LiquidationOrder liquidation) {
        return liquidation.status() != CoreLiquidationState.Status.COMPLETED
                && liquidation.status() != CoreLiquidationState.Status.CANCELED;
    }

    /**
     * 为未完成且未取消的强平建立用户、标的、方向索引，同一持仓方向出现不同活动强平时拒绝。
     */
    public static void indexActiveLiquidation(AccountPartition lane, RiskScanner.LiquidationOrder liquidation) {
        if (!active(liquidation)) return;
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidations(liquidation.userId());
        if (bySymbol == null) {
            bySymbol = new IntObjectHashMap<>();
            lane.storeActiveLiquidations(liquidation.userId(), bySymbol);
        }
        LongObjectHashMap<Long> bySide = bySymbol.get(liquidation.symbolId());
        if (bySide == null) {
            bySide = new LongObjectHashMap<>();
            bySymbol.put(liquidation.symbolId(), bySide);
        }
        Long current = bySide.get(liquidation.positionSide().ordinal());
        if (current != null && current != liquidation.liquidationId()) {
            throw new IllegalStateException("multiple active runtime liquidations for one position");
        }
        bySide.put(liquidation.positionSide().ordinal(), liquidation.liquidationId());
    }

    /**
     * 旧强平仍属活动分类时删除其方向索引，并逐层清理空标的与用户分组。
     */
    public static void removeActiveLiquidation(AccountPartition lane, RiskScanner.LiquidationOrder liquidation) {
        if (!active(liquidation)) return;
        IntObjectHashMap<LongObjectHashMap<Long>> bySymbol = lane.activeLiquidations(liquidation.userId());
        LongObjectHashMap<Long> bySide = bySymbol == null ? null : bySymbol.get(liquidation.symbolId());
        if (bySide == null) return;
        bySide.remove(liquidation.positionSide().ordinal());
        if (bySide.isEmpty()) bySymbol.remove(liquidation.symbolId());
        if (bySymbol.isEmpty()) lane.removeActiveLiquidations(liquidation.userId());
    }
}
