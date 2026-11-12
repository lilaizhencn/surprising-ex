package com.surprising.aeron.service.core.index;

import com.surprising.aeron.service.core.change.StateChangeBuffer;
import com.surprising.aeron.service.core.identity.IdIndex;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.settlement.TradeSettlement;
import lombok.Getter;
import lombok.experimental.Accessors;

/** 把账户已发布变化应用到现有派生索引的入口。Owner 维护订单及条件任务轻量路由，持仓用户、未平仓量及 ADL 业务索引可绑定给 System Ledger。 */
public final class BizIndex {
    /** 现有标的到持仓用户的派生索引，可绑定业务线程。 */
    @Getter
    @Accessors(fluent = true, chain = false)
    private final PositionUserIndex positionUsers;
    /** 现有标的未平仓量索引，可绑定业务线程。 */
    @Getter
    @Accessors(fluent = true, chain = false)
    private final OpenInterestIndex openInterest;
    /** Owner 的触发单路由和价格候选索引。 */
    @Getter
    @Accessors(fluent = true, chain = false)
    private final TriggerOrderIndex triggers;
    /** Owner 的算法单身份与调度索引。 */
    @Getter
    @Accessors(fluent = true, chain = false)
    private final AlgoOrderIndex algos;
    /** Owner 的强平轻量路由及未结束集合。 */
    @Getter
    @Accessors(fluent = true, chain = false)
    private final LiquidationIndex liquidations;
    /** Owner 的延时全撤计时索引。 */
    @Getter
    @Accessors(fluent = true, chain = false)
    private final CancelAllAfterIndex timers;
    /** Owner 的 OPEN 订单轻量路由。 */
    @Getter
    @Accessors(fluent = true, chain = false)
    private final ActiveOrderIndex activeOrders;
    /** 现有 ADL 持仓索引，可变单元提供更新前事实，可绑定业务线程。 */
    @Getter
    @Accessors(fluent = true, chain = false)
    private final AdlPositionIndex adlPositions;

    /** 要求八个现有索引全部存在，保存引用。 */
    public BizIndex(PositionUserIndex positionUsers, OpenInterestIndex openInterest,
                                TriggerOrderIndex triggers, AlgoOrderIndex algos,
                                LiquidationIndex liquidations, CancelAllAfterIndex timers,
                                ActiveOrderIndex activeOrders, AdlPositionIndex adlPositions) {
        this.positionUsers = require(positionUsers, "position-user");
        this.openInterest = require(openInterest, "open-interest");
        this.triggers = require(triggers, "trigger");
        this.algos = require(algos, "algo");
        this.liquidations = require(liquidations, "liquidation");
        this.timers = require(timers, "timer");
        this.activeOrders = require(activeOrders, "active-order");
        this.adlPositions = require(adlPositions, "ADL-position");
    }

    /** 只把持仓用户、未平仓量及 ADL 三个业务索引绑定到指定线程。其他 Owner 路由不随之转移。 */
    public void bindBusinessOwner(Thread thread) {
        positionUsers.bindBusinessOwner(thread); openInterest.bindBusinessOwner(thread); adlPositions.bindBusinessOwner(thread);
    }

    /** 账本消费 laneMask 指定的冻结持仓发布缓冲，逐 Lane 更新业务索引。外层在消费完成之前保留结算结果租约。 */
    public void applySettlementPositions(TradeSettlement.Changes changes, long laneMask,
            IdIndex identities) {
        while (laneMask != 0) {
            int laneId = Long.numberOfTrailingZeros(laneMask);
            laneMask &= laneMask - 1;
            applyPositions(changes.laneDelta(laneId).positions(), identities, null);
        }
    }

    /** 逐条优先使用冻结的 prepared 值。已准备而值为空明确表示删除，不能回退到可变正文。 */
    public void applyPositions(StateChangeBuffer.Indexed<Position, Position.IndexValue> positions, IdIndex identities,
            PositionUndo undo) {
        for (int slot = 0; slot < positions.size(); slot++) {
            long key = positions.keyAt(slot);
            Position value = positions.valueAt(slot);
            Position.IndexValue prepared = positions.preparedAt(slot);
            boolean hasPrepared = positions.hasPreparedAt(slot);
            long userId = hasPrepared ? prepared == null ? 0 : prepared.userId() : value == null ? 0 : value.userId();
            String symbol = hasPrepared ? prepared == null ? null : prepared.instrumentId()
                    : value == null ? null : identities.instrumentId(value.symbolId());
            String asset = hasPrepared ? prepared == null ? null : prepared.asset()
                    : value == null ? null : identities.asset(value.assetId());
            var side = hasPrepared ? prepared == null ? null : prepared.positionSide()
                    : value == null ? null : value.positionSide();
            long quantity = hasPrepared ? prepared == null ? 0 : prepared.signedQuantitySteps()
                    : value == null ? 0 : value.signedQuantitySteps();
            var previous = adlPositions.value(key);
            if (previous == null && userId == 0 || previous != null && previous.userId() == userId
                    && previous.signedQuantitySteps() == quantity
                    && java.util.Objects.equals(previous.instrumentId(), symbol)
                    && java.util.Objects.equals(previous.asset(), asset) && previous.positionSide() == side) continue;
            if (undo != null) undo.capture(key, previous);
            applyPosition(key, previous, userId, symbol, asset, side, quantity);
        }
    }

    /** 先处理控制命令直接缓冲，再按保留位图处理各 Lane 缓冲，按需记录原值供未发布控制范围撤销。 */
    public void applyControlPositions(StateChangeBuffer.OwnerIndexed<Position, Position.IndexValue> positions,
            IdIndex identities, PositionUndo undo) {
        applyPositions(positions.directBuffer(), identities, undo);
        long remaining = positions.retainedLaneMask();
        while (remaining != 0) {
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            applyPositions(positions.laneBuffer(lane), identities, undo);
        }
    }

    /** 尚未发布控制范围的持仓原值记录，平行数组只复制必要字段。 */
    public static final class PositionUndo {
        private int size;
        /** keys：每槽对应的确定性持仓身份键。 */
        private long[] keys = new long[8], users = new long[8], quantities = new long[8];
        private String[] symbols = new String[8], assets = new String[8];
        private com.surprising.aeron.protocol.CorePositionSide[] sides = new com.surprising.aeron.protocol.CorePositionSide[8];

        /** 复制本次更新前的用户、标的、资产、方向及数量，不保留会被原地修改的索引单元。 */
        void capture(long key, AdlPositionIndex.IndexedPosition previous) {
            if (size == keys.length) {
                int capacity = Math.multiplyExact(size, 2);
                keys = java.util.Arrays.copyOf(keys, capacity); users = java.util.Arrays.copyOf(users, capacity);
                quantities = java.util.Arrays.copyOf(quantities, capacity);
                symbols = java.util.Arrays.copyOf(symbols, capacity); assets = java.util.Arrays.copyOf(assets, capacity);
                sides = java.util.Arrays.copyOf(sides, capacity);
            }
            keys[size] = key;
            users[size] = previous == null ? 0 : previous.userId();
            quantities[size] = previous == null ? 0 : previous.signedQuantitySteps();
            symbols[size] = previous == null ? null : previous.instrumentId();
            assets[size] = previous == null ? null : previous.asset();
            sides[size] = previous == null ? null : previous.positionSide();
            size++;
        }

        /** 按捕获顺序倒序恢复三个持仓索引，再清空记录。 */
        public void rollback(BizIndex indexes) {
            for (int slot = size - 1; slot >= 0; slot--)
                indexes.applyPosition(keys[slot], users[slot], symbols[slot], assets[slot], sides[slot], quantities[slot]);
            clear();
        }

        /** 清掉有效范围的文本及方向引用并归零条数，保留数组容量和无引用的数字槽供复用。 */
        public void clear() {
            java.util.Arrays.fill(symbols, 0, size, null); java.util.Arrays.fill(assets, 0, size, null);
            java.util.Arrays.fill(sides, 0, size, null); size = 0;
        }
    }

    private void applyPosition(long key, long userId, String symbol, String asset,
            com.surprising.aeron.protocol.CorePositionSide side, long quantity) {
        applyPosition(key, adlPositions.value(key), userId, symbol, asset, side, quantity);
    }

    private void applyPosition(long key, AdlPositionIndex.IndexedPosition previous,
            long userId, String symbol, String asset, com.surprising.aeron.protocol.CorePositionSide side, long quantity) {
        positionUsers.applyRoutes(previous == null ? 0 : previous.userId(), previous == null ? null : previous.instrumentId(), userId, symbol);
        openInterest.applyQuantities(previous == null ? null : previous.instrumentId(),
                previous == null ? 0 : previous.signedQuantitySteps(), symbol, quantity);
        adlPositions.apply(key, previous, userId, symbol, asset, side, quantity);
    }

    /** 把本次持仓新状态转为用户、永久标的、资产、方向及数量。 */
    public void applyPosition(long positionKey, Position after, IdIndex identities) {
        applyPosition(positionKey, after == null ? 0 : after.userId(),
                after == null ? null : identities.instrumentId(after.symbolId()),
                after == null ? null : identities.asset(after.assetId()),
                after == null ? null : after.positionSide(), after == null ? 0 : after.signedQuantitySteps());
    }

    private static <T> T require(T index, String name) {
        if (index == null) throw new IllegalArgumentException(name + " index is required");
        return index;
    }

}
