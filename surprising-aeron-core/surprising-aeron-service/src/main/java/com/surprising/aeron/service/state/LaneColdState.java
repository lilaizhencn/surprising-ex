package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreAlgoOrderState;
import com.surprising.aeron.service.state.model.CoreLeverageKey;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

/**
 * Lane 状态中不参与持续撮合的索引集合。
 *
 * <p>把风险、触发、清算和杠杆结构放在独立对象中，AccountLaneState 的热对象只保留
 * 订单、余额、预留和持仓引用。冷状态仍由同一个 Lane 线程拥有，因而不引入新的锁或
 * 可见性协议；快照、回放和查询继续使用原来的索引。</p>
 */
final class LaneColdState {
    final LongObjectHashMap<LiquidationRuntime> liquidations = new LongObjectHashMap<>();
    final LongObjectHashMap<IntObjectHashMap<LongObjectHashMap<Long>>> activeLiquidationIndex =
            new LongObjectHashMap<>();
    final LongObjectHashMap<RiskSnapshotRuntime> riskSnapshots = new LongObjectHashMap<>();
    final Map<CoreLeverageKey, Long> leverages = new HashMap<>();
    final LongObjectHashMap<HashSet<CoreLeverageKey>> leverageKeysByUser = new LongObjectHashMap<>();
    final LongObjectHashMap<CoreAlgoOrderState> algoOrders = new LongObjectHashMap<>();
    final LongObjectHashMap<CoreTriggerOrderState> triggerOrders = new LongObjectHashMap<>();
    final LongObjectHashMap<LongHashSet> triggerIdsByUser = new LongObjectHashMap<>();

    void clear() {
        liquidations.clear();
        activeLiquidationIndex.clear();
        riskSnapshots.clear();
        leverages.clear();
        leverageKeysByUser.clear();
        algoOrders.clear();
        triggerOrders.clear();
        triggerIdsByUser.clear();
    }
}
