package com.surprising.aeron.service.core.index;

import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.service.core.change.OwnerIndexedChanges;
import com.surprising.aeron.service.core.change.RuntimeIndexedChangeBuffer;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.position.RuntimePositionIndexValue;
import com.surprising.aeron.service.settlement.MatcherSettlementChanges;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 把账户已发布变化应用到现有派生索引的入口。Owner 维护订单及条件任务轻量路由，持仓用户、未平仓量及 ADL 业务索引可绑定给 System Ledger；不另存账户正文，不把各索引更新包装成自动事务。
 */
public final class RuntimeFactIndexes {
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

    /**
     * 要求八个现有索引全部存在，保存引用；不新建另一套业务状态。
     * @param positionUsers 已有的持仓用户索引
     * @param openInterest 已有的未平仓量索引
     * @param triggers 已有的触发单索引
     * @param algos 已有的算法单索引
     * @param liquidations 已有的强平索引
     * @param timers 已有的全撤计时器索引
     * @param activeOrders 已有的活跃订单路由索引
     * @param adlPositions 已有的 ADL 持仓索引
     */
    public RuntimeFactIndexes(PositionUserIndex positionUsers, OpenInterestIndex openInterest,
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

    /**
     * 只把持仓用户、未平仓量及 ADL 三个业务索引绑定到指定线程；其他 Owner 路由不随之转移。
     * @param thread 三个持仓业务索引新的权威线程
     */
    public void bindBusinessOwner(Thread thread) {
        positionUsers.bindBusinessOwner(thread); openInterest.bindBusinessOwner(thread); adlPositions.bindBusinessOwner(thread);
    }

    /**
     * 账本消费 laneMask 指定的冻结持仓发布缓冲，逐 Lane 更新业务索引；外层在消费完成之前保留结算结果租约。
     * @param changes 已冻结并保留租约的 Matcher 结算变化
     * @param laneMask 本次需要消费的账户 Lane 位图
     * @param identities 内部资产及标的编号对应的已准备身份表
     */
    public void applySettlementPositions(MatcherSettlementChanges changes, long laneMask,
            RuntimeIdentityRegistry identities) {
        while (laneMask != 0) {
            // 当前从结算 Lane 位图取出的最低置位下标
            int laneId = Long.numberOfTrailingZeros(laneMask);
            laneMask &= laneMask - 1;
            applyPositions(changes.laneDelta(laneId).positions(), identities, null);
        }
    }

    /**
     * 逐条优先使用冻结的 prepared 值；已准备而值为空明确表示删除，不能回退到可变正文。与旧 ADL 项完全相同则跳过，有撤销记录时先复制旧字段再更新三项持仓索引。
     * @param positions 本次持仓变化缓冲，prepared 标记决定是否使用冻结值
     * @param identities 内部资产及标的编号对应的已准备身份表
     * @param undo 可空的控制范围持仓撤销记录，不需要回滚时为空
     */
    public void applyPositions(RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue> positions, RuntimeIdentityRegistry identities,
            PositionUndo undo) {
        for (/* 当前消费的变化槽，或倒序撤销的原值槽 */ int slot = 0; slot < positions.size(); slot++) {
            // 当前槽的确定性持仓身份键
            long key = positions.keyAt(slot);
            // 原账户可变持仓正文，仅在没有 prepared 标记时读取
            PositionRuntime value = positions.valueAt(slot);
            // 本槽冻结持仓值；hasPrepared 为真且此值为空表示权威删除
            RuntimePositionIndexValue prepared = positions.preparedAt(slot);
            // 是否已经冻结本槽结果，与 prepared 是否为空分别判断
            boolean hasPrepared = positions.hasPreparedAt(slot);
            // 本槽解析出的新用户，删除为零
            long userId = hasPrepared ? prepared == null ? 0 : prepared.userId() : value == null ? 0 : value.userId();
            // 本槽解析出的新永久标的文本，删除为空
            String symbol = hasPrepared ? prepared == null ? null : prepared.instrumentId()
                    : value == null ? null : identities.instrumentId(value.symbolId());
            // 本槽解析出的新资产文本，删除为空
            String asset = hasPrepared ? prepared == null ? null : prepared.asset()
                    : value == null ? null : identities.asset(value.assetId());
            // 本槽解析出的新持仓方向，删除为空
            var side = hasPrepared ? prepared == null ? null : prepared.positionSide()
                    : value == null ? null : value.positionSide();
            // 本槽解析出的新带符号数量步数，删除为零
            long quantity = hasPrepared ? prepared == null ? 0 : prepared.signedQuantitySteps()
                    : value == null ? 0 : value.signedQuantitySteps();
            // 当前 ADL 旧索引单元，可能随后被原地改写
            var previous = adlPositions.value(key);
            if (previous == null && userId == 0 || previous != null && previous.userId() == userId
                    && previous.signedQuantitySteps() == quantity
                    && java.util.Objects.equals(previous.instrumentId(), symbol)
                    && java.util.Objects.equals(previous.asset(), asset) && previous.positionSide() == side) continue;
            if (undo != null) undo.capture(key, previous);
            applyPosition(key, previous, userId, symbol, asset, side, quantity);
        }
    }

    /**
     * 先处理控制命令直接缓冲，再按保留位图处理各 Lane 缓冲，按需记录原值供未发布控制范围撤销。
     * @param positions 本次持仓变化缓冲，prepared 标记决定是否使用冻结值
     * @param identities 内部资产及标的编号对应的已准备身份表
     * @param undo 可空的控制范围持仓撤销记录，不需要回滚时为空
     */
    public void applyControlPositions(OwnerIndexedChanges<PositionRuntime, RuntimePositionIndexValue> positions,
            RuntimeIdentityRegistry identities, PositionUndo undo) {
        applyPositions(positions.directBuffer(), identities, undo);
        // 还未消费的控制变化保留 Lane 位图
        long remaining = positions.retainedLaneMask();
        while (remaining != 0) {
            // 当前从控制变化位图取出的最低置位 Lane 下标
            int lane = Long.numberOfTrailingZeros(remaining); remaining &= remaining - 1;
            applyPositions(positions.laneBuffer(lane), identities, undo);
        }
    }

    /**
     * 尚未发布控制范围的持仓原值记录，平行数组只复制必要字段；不缓存可变 ADL 单元，也不记录其他类型索引。
     */
    public static final class PositionUndo {
        /** 当前已保存原值的条数，同键多次更新可以占多槽。 */
        private int size;
        /**
         * keys：每槽对应的确定性持仓身份键。
         * users：每槽旧用户，零表示原先无持仓索引项。
         * quantities：每槽旧的带符号持仓数量步数。
         */
        private long[] keys = new long[8], users = new long[8], quantities = new long[8];
        /**
         * symbols：每槽旧永久标的文本，clear 时释放引用。
         * assets：每槽旧资产文本，clear 时释放引用。
         */
        private String[] symbols = new String[8], assets = new String[8];
        /** 每槽旧持仓方向，clear 时释放引用。 */
        private com.surprising.aeron.protocol.CorePositionSide[] sides = new com.surprising.aeron.protocol.CorePositionSide[8];

        /**
         * 复制本次更新前的用户、标的、资产、方向及数量，不保留会被原地修改的索引单元；允许同一键多次记录，容量不足时所有并行数组一起扩容。
         * @param key 待更新持仓的确定性长整数键
         * @param previous 更新前的 ADL 索引单元，捕获原值时立即复制字段
         */
        void capture(long key, AdlPositionIndex.IndexedPosition previous) {
            if (size == keys.length) {
                // 撤销记录所有并行数组本次扩容后的目标长度
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

        /**
         * 按捕获顺序倒序恢复三个持仓索引，再清空记录；中途异常不保证整次撤销已经完成。
         * @param indexes 恢复原持仓字段时使用的同一组派生索引
         */
        public void rollback(RuntimeFactIndexes indexes) {
            for (/* 当前消费的变化槽，或倒序撤销的原值槽 */ int slot = size - 1; slot >= 0; slot--)
                indexes.applyPosition(keys[slot], users[slot], symbols[slot], assets[slot], sides[slot], quantities[slot]);
            clear();
        }

        /**
         * 清掉有效范围的文本及方向引用并归零条数，保留数组容量和无引用的数字槽供复用。
         */
        public void clear() {
            java.util.Arrays.fill(symbols, 0, size, null); java.util.Arrays.fill(assets, 0, size, null);
            java.util.Arrays.fill(sides, 0, size, null); size = 0;
        }
    }

    /**
     * 直接应用已冻结的持仓索引值，空值表示删除，不回读 Lane 可变持仓。
     * @param positionKey 当前持仓的确定性身份键
     * @param current 冻结的当前持仓索引值，空值删除
     */
    public void preparedPosition(long positionKey, RuntimePositionIndexValue current) {
        applyPosition(positionKey, current == null ? 0 : current.userId(), current == null ? null : current.instrumentId(),
                current == null ? null : current.asset(), current == null ? null : current.positionSide(),
                current == null ? 0 : current.signedQuantitySteps());
    }

    /**
     * 从现有 ADL 表读取旧索引单元，再以新持仓字段更新三个业务索引。
     * @param key 待更新持仓的确定性长整数键
     * @param userId 变更后用户，零表示删除
     * @param symbol 变更后永久标的文本，删除时为空
     * @param asset 变更后资产文本，删除时为空
     * @param side 变更后持仓方向，删除时为空
     * @param quantity 变更后带符号数量步数
     */
    private void applyPosition(long key, long userId, String symbol, String asset,
            com.surprising.aeron.protocol.CorePositionSide side, long quantity) {
        applyPosition(key, adlPositions.value(key), userId, symbol, asset, side, quantity);
    }

    /**
     * 依次迁移持仓用户路由、更新未平仓量，再更新 ADL 单元；用户零及空路由表示删除，各步失败交给外层错误边界处理。
     * @param key 待更新持仓的确定性长整数键
     * @param previous 更新前的 ADL 索引单元，捕获原值时立即复制字段
     * @param userId 变更后用户，零表示删除
     * @param symbol 变更后永久标的文本，删除时为空
     * @param asset 变更后资产文本，删除时为空
     * @param side 变更后持仓方向，删除时为空
     * @param quantity 变更后带符号数量步数
     */
    private void applyPosition(long key, AdlPositionIndex.IndexedPosition previous,
            long userId, String symbol, String asset, com.surprising.aeron.protocol.CorePositionSide side, long quantity) {
        positionUsers.applyRoutes(previous == null ? 0 : previous.userId(), previous == null ? null : previous.instrumentId(), userId, symbol);
        openInterest.applyQuantities(previous == null ? null : previous.instrumentId(),
                previous == null ? 0 : previous.signedQuantitySteps(), symbol, quantity);
        adlPositions.apply(key, previous, userId, symbol, asset, side, quantity);
    }

    /**
     * 把本次持仓新状态转为用户、永久标的、资产、方向及数量；空值删除，旧事实从 ADL 索引读取。
     * @param positionKey 当前持仓的确定性身份键
     * @param identities 运行时已经绑定的资产及标的身份表
     * @param after 变更后的真实状态，空值表示删除
     */
    public void applyPosition(long positionKey, PositionRuntime after, RuntimeIdentityRegistry identities) {
        applyPosition(positionKey, after == null ? 0 : after.userId(),
                after == null ? null : identities.instrumentId(after.symbolId()),
                after == null ? null : identities.asset(after.assetId()),
                after == null ? null : after.positionSide(), after == null ? 0 : after.signedQuantitySteps());
    }

    /**
     * 在冷边界依次重建全部八个索引；这不是候选集合的一次原子替换，中途失败交给恢复边界处理。
     * @param state 冷边界重建全部索引使用的已提交状态
     * @param identities 内部资产及标的编号对应的已准备身份表
     */
    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        positionUsers.rebuild(state);
        openInterest.rebuild(state);
        triggers.rebuild(state);
        algos.rebuild(state);
        liquidations.rebuild(state);
        timers.rebuild(state);
        activeOrders.rebuild(state, identities);
        adlPositions.rebuild(state, identities);
    }

    /**
     * 要求传入索引存在并原样返回，只附带具体索引名称诊断。
     * @param <T> 原索引引用类型，仅保留参数类型返回
     * @param index 必须存在的既有索引引用
     * @param name 用于缺失异常诊断的索引业务名
     */
    private static <T> T require(T index, String name) {
        if (index == null) throw new IllegalArgumentException(name + " index is required");
        return index;
    }

}
