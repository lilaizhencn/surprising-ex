package com.surprising.aeron.service.core.index;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.InstrumentId;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.order.OrderFundsReservation.Snapshot;
import com.surprising.aeron.service.risk.RiskScanner;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

/** Owner 强平路由及未结束任务索引。 */
public final class LiquidationIndex {

    private final Map<LiquidationKey, NavigableSet<Long>> activeIds = new TreeMap<>();
    private final NavigableSet<Long> allActiveIds = new TreeSet<>();
    private final LongObjectHashMap<LiquidationRoute> keysById = new LongObjectHashMap<>();

    /** 从已提交冷状态重建强平路由及未结束 ID 分组。 */
    public LiquidationIndex(TradingCoreState state) {
        rebuild(state);
    }

    /** 借出原可变的全局未结束 ID 升序集合。调用方沿用 Owner 访问边界，直接改集合会使分组索引不一致。 */
    public NavigableSet<Long> activeIds() {
        return allActiveIds;
    }

    /** 强平路由的不可变用户、标的、方向及未结束/可执行标志，不保留资金和持仓正文。 */
    public record LiquidationRoute(long userId, String instrumentId, CorePositionSide positionSide,
                                   boolean unresolved, boolean executable) {
        /** 从轻量路由生成用户、标的及持仓方向分组键。 */
        LiquidationKey key() { return new LiquidationKey(userId, instrumentId, positionSide); }
    }
    /** 按强平 ID 读取最近应用的轻量路由，包括保留的终态路由。 */
    public LiquidationRoute route(long id) { return keysById.get(id); }

    /** 遍历路由找未结束任务。 */
    public boolean hasUnresolved(String instrumentId) {
        for (LiquidationRoute route : keysById.values())
            if (route.unresolved() && (instrumentId == null || route.instrumentId().equals(instrumentId))) return true;
        return false;
    }

    /** 先按旧路由撤掉未结束集合，再从账户运行时状态建立新路由。 */
    public void apply(long liquidationId, RiskScanner.LiquidationOrder after, RuntimeIdentityRegistry identities) {
        LiquidationRoute previous = keysById.remove(liquidationId);
        if (previous != null && previous.unresolved()) remove(liquidationId, previous.key());
        if (after != null) {
            LiquidationRoute current = new LiquidationRoute(after.userId(), identities.instrumentId(after.symbolId()),
                    after.positionSide(), isActive(after), after.status() == CoreLiquidationState.Status.PLANNED
                    || after.status() == CoreLiquidationState.Status.ORDERED);
            keysById.put(liquidationId, current);
            if (current.unresolved()) add(liquidationId, current.key());
        }
    }

    /** 清全局、分组及反向路由表，再从冷状态风险分量的强平逐个重建。 */
    public void rebuild(TradingCoreState state) {
        activeIds.clear();
        allActiveIds.clear();
        keysById.clear();
        state.riskState().liquidations().values().forEach(this::add);
    }

    private static boolean isActive(CoreLiquidationState value) {
        return value != null && value.status() != CoreLiquidationState.Status.COMPLETED
                && value.status() != CoreLiquidationState.Status.CANCELED;
    }

    private static boolean isActive(RiskScanner.LiquidationOrder value) {
        return value != null && value.status() != CoreLiquidationState.Status.COMPLETED
                && value.status() != CoreLiquidationState.Status.CANCELED;
    }

    private void add(CoreLiquidationState value) {
        LiquidationKey key = new LiquidationKey(value.userId(), value.instrumentId(), value.positionSide());
        keysById.put(value.liquidationId(), new LiquidationRoute(value.userId(), value.instrumentId(),
                value.positionSide(), isActive(value), value.status() == CoreLiquidationState.Status.PLANNED
                || value.status() == CoreLiquidationState.Status.ORDERED));
        if (isActive(value)) add(value.liquidationId(), key);
    }

    private void add(long liquidationId, LiquidationKey key) {
        allActiveIds.add(liquidationId);
        activeIds.computeIfAbsent(key, ignored -> new TreeSet<>()).add(liquidationId);
    }

    private void remove(long liquidationId, LiquidationKey key) {
        allActiveIds.remove(liquidationId);
        NavigableSet<Long> ids = activeIds.get(key);
        if (ids == null) return;
        ids.remove(liquidationId);
        if (ids.isEmpty()) activeIds.remove(key);
    }

    /** 未结束强平的用户、标的和持仓方向分组键。 */
    private record LiquidationKey(long userId, String instrumentId, CorePositionSide positionSide)
            implements Comparable<LiquidationKey> {
        private LiquidationKey {
            instrumentId = InstrumentId.requireValid(instrumentId);
        }

        /** 按用户 ID、标的文本字典序、持仓方向枚举序号比较，不把标的文本当作数值大小排序。 */
        @Override
        public int compareTo(LiquidationKey other) {
            int user = Long.compare(userId, other.userId);
            if (user != 0) return user;
            int symbolCompare = instrumentId.compareTo(other.instrumentId);
            return symbolCompare != 0 ? symbolCompare : Integer.compare(positionSide.ordinal(), other.positionSide.ordinal());
        }
    }
}
