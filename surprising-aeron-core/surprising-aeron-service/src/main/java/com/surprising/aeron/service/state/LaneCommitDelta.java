package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.risk.RiskSnapshotRuntime;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

public final class LaneCommitDelta {
    /** Owner 串行应用后的变更集合与终态回执，提交完成前保留运行态引用。 */
    private boolean publicationPrepared;
    /** Lane 准备的终态原语收据；Owner 不再重新遍历订单或创建 OrderRuntime 快照。 */
    private long[] terminalOrderIds = new long[4];
    private long[] terminalOrderUsers = new long[4];
    private String[] terminalOrderClients = new String[4];
    private int terminalOrderCount;

    private OrderRuntime[] retiredOrders = new OrderRuntime[4];
    private int retiredOrderCount;

    void retiredOrder(OrderRuntime order) {
        if (retiredOrderCount == retiredOrders.length)
            retiredOrders = java.util.Arrays.copyOf(retiredOrders, retiredOrders.length * 2);
        retiredOrders[retiredOrderCount++] = order;
    }

    void returnRetiredOrders(AccountLaneState lane) {
        for (int index = 0; index < retiredOrderCount; index++) {
            lane.returnRetiredOrder(retiredOrders[index]);
            retiredOrders[index] = null;
        }
        retiredOrderCount = 0;
    }

    void ensureTerminalCapacity(int expectedCount) {
        if (expectedCount <= terminalOrderIds.length) return;
        int capacity = terminalOrderIds.length;
        while (capacity < expectedCount) capacity = Math.multiplyExact(capacity, 2);
        terminalOrderIds = java.util.Arrays.copyOf(terminalOrderIds, capacity);
        terminalOrderUsers = java.util.Arrays.copyOf(terminalOrderUsers, capacity);
        terminalOrderClients = java.util.Arrays.copyOf(terminalOrderClients, capacity);
    }

    void recordTerminalOrder(OrderRuntime value) {
        if (terminalOrderCount == terminalOrderIds.length) {
            ensureTerminalCapacity(Math.addExact(terminalOrderCount, 1));
        }
        terminalOrderIds[terminalOrderCount] = value.orderId();
        terminalOrderUsers[terminalOrderCount] = value.userId();
        terminalOrderClients[terminalOrderCount] = value.clientOrderId();
        terminalOrderCount++;
    }

    void preparePublication() {
        if (publicationPrepared) throw new IllegalStateException("Lane publication already prepared");
        if (terminalOrderCount == 0) {
            orders.forEach((orderId, order) -> {
                if (order != null && order.status().terminal()) recordTerminalOrder(order);
            });
        }
        orders.freezeValues();
        reservations.freezeValues();
        publicationPrepared = true;
    }

    /** Returns terminal identity data already collected by the Lane; only indices below count are valid. */
    public int terminalOrderCount() { return terminalOrderCount; }
    public long terminalOrderId(int index) { return terminalOrderIds[index]; }
    public long terminalOrderUser(int index) { return terminalOrderUsers[index]; }
    public String terminalOrderClient(int index) { return terminalOrderClients[index]; }

    /** 本次需要发布的触发单变化。 */
    RuntimeChangeBuffer<CoreTriggerOrderState> triggers;
    RuntimeChangeBuffer<com.surprising.aeron.service.state.model.CoreAlgoOrderState> algos;
    int closedTriggerCount;
    /** 本次需要发布的用户变化。 */
    final RuntimeChangeBuffer<UserRuntime> users = new RuntimeChangeBuffer<>();
    /** 本次需要发布的订单变化。 */
    final RuntimeOrderChangeBuffer orders = new RuntimeOrderChangeBuffer();
    /** 本次需要发布的预留变化。 */
    final RuntimeReservationChangeBuffer reservations = new RuntimeReservationChangeBuffer();
    /** 本次需要发布的持仓变化。 */
    final RuntimePositionChangeBuffer positions = new RuntimePositionChangeBuffer();
    /** 本次需要发布的清算变化。 */
    final RuntimeChangeBuffer<LiquidationRuntime> liquidations = new RuntimeChangeBuffer<>();
    /** 本次需要发布的风险快照变化。 */
    /** 删除的预计算列只携带Lane末尾持仓不存在的证明，供提交后回收身份。 */
    final RuntimeIndexedChangeBuffer<RiskSnapshotRuntime, Boolean> riskSnapshots = new RuntimeIndexedChangeBuffer<>();
    /** 待移除的订单路由 ID。 */
    final LongHashSet removedOrderRoutes = new LongHashSet();
    /** 待移除的预留路由 ID。 */
    final LongHashSet removedReservationRoutes = new LongHashSet();

    void ensureAdmissionCapacity(int expectedOrders) {
        users.ensureCapacity(1);
        orders.ensureCapacity(expectedOrders);
        reservations.ensureCapacity(expectedOrders);
    }

    void ensureOrderCapacity(int expectedOrders) {
        users.ensureCapacity(expectedOrders * 2);
        orders.ensureCapacity(expectedOrders);
        reservations.ensureCapacity(expectedOrders);
        positions.ensureCapacity(expectedOrders * 2);
    }

    void putUser(long key, UserRuntime value) {
        users.put(key, value);
    }

    void putOrder(long key, OrderRuntime value) {
        orders.putLive(key, value);
    }

    void putReservation(long key, ReservationRuntime value) {
        reservations.putLive(key, value);
    }

    /** The Account worker freezes its output before publishing the control completion bit. */
    void freezeControlValues() { orders.freezeValues(); reservations.freezeValues(); }

    void putPosition(long key, PositionRuntime value) {
        positions.put(key, value);
    }

    void putLiquidation(long key, LiquidationRuntime value) {
        liquidations.put(key, value);
    }

    void putRiskSnapshot(long key, RiskSnapshotRuntime value) {
        riskSnapshots.put(key, value);
    }

    /** 只在所属Lane结束命令时采集删除证明，不传输账户状态。 */
    void preparePositionRetirement(AccountLaneState lane) {
        for (int index = 0; index < riskSnapshots.size(); index++) {
            if (riskSnapshots.valueAt(index) != null) continue;
            long key = riskSnapshots.keyAt(index);
            riskSnapshots.putPrepared(key, lane.positions.get(key) == null);
        }
    }

    void removeOrderRoute(long orderId) { removedOrderRoutes.add(orderId); }
    void removeReservationRoute(long orderId) { removedReservationRoutes.add(orderId); }

    void drainTo(int laneId,
                 TradingRuntimeState state,
                 com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedUsers,
                 com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedOrders) {
        if (changedUsers != null || changedOrders != null) {
            users.forEach((userId, ignored) -> {
                if (changedUsers != null) changedUsers.add(userId);
            });
            orders.forEach((orderId, ignored) -> {
                if (changedOrders != null) changedOrders.add(orderId);
            });
            reservations.forEach((orderId, reservation) -> {
                if (changedOrders != null) changedOrders.add(orderId);
                if (changedUsers != null && reservation != null) changedUsers.add(reservation.userId());
            });
            positions.forEach((positionKey, position) -> {
                if (changedUsers != null && position != null) changedUsers.add(position.userId());
            });
            if (changedOrders != null) removedOrderRoutes.forEach(changedOrders::add);
        }
        users.clear();
        orders.clear();
        reservations.clear();
        positions.clear();
        liquidations.clear();
        riskSnapshots.drainTo((key, value) -> {
            if (value == null) state.riskPositionIdentities.remove(key);
            else state.riskPositionIdentities.add(key);
        });
        if (!removedOrderRoutes.isEmpty()) removedOrderRoutes.clear();
        if (!removedReservationRoutes.isEmpty()) removedReservationRoutes.clear();
    }

    void commitTerminalToOwner(TradingRuntimeState state, int laneId,
                               TradingRuntimeState.TerminalOrderSink terminalOrderSink, long coreSequence,
                               com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedUsers,
                               com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedOrders) {
        publishTriggersToOwner(state);
        if (closedTriggerCount != 0) {
            state.revision = Math.addExact(state.revision, closedTriggerCount);
            closedTriggerCount = 0;
        }
        if (!publicationPrepared) throw new IllegalStateException("Lane terminal publication is not prepared");
        publishPreparedToOwner(state, changedUsers, changedOrders);
        if (terminalOrderSink != null && terminalOrderCount != 0)
            terminalOrderSink.acceptBatch(this, coreSequence);
        for (int index = 0; index < liquidations.size(); index++)
            state.changedLiquidations.put(liquidations.keyAt(index), liquidations.valueAt(index));
        liquidations.clear();
        for (int index = 0; index < riskSnapshots.size(); index++) {
            long key = riskSnapshots.keyAt(index);
            RiskSnapshotRuntime value = riskSnapshots.valueAt(index);
            state.changedRiskSnapshots.putIndexed(key, value, riskSnapshots.hasPreparedAt(index), riskSnapshots.preparedAt(index));
            if (value == null) state.riskPositionIdentities.remove(key);
            else state.riskPositionIdentities.add(key);
        }
        riskSnapshots.clear();
        state.changedOrders.adopt(laneId, orders);
        state.changedPositions.adopt(laneId, positions);
    }

    private void publishPreparedToOwner(
            TradingRuntimeState state,
            com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedUsers,
            com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedOrders) {
        // 单写者架构：零 Map 复制与搬运，仅收集变更集合用于下游输出
        users.drainTo((id, value) -> {
            if (changedUsers != null) changedUsers.add(id);
            state.changedUsers.add(id);
            state.captureUserMetadata(value);
        });
        for (int index = 0; index < orders.size(); index++) {
            long id = orders.keyAt(index);
            if (changedOrders != null) changedOrders.add(id);
        }
        for (int index = 0; index < reservations.size(); index++) {
            long id = reservations.keyAt(index);
            ReservationRuntime value = reservations.valueAt(index);
            if (changedOrders != null) changedOrders.add(id);
            if (changedUsers != null && value != null) changedUsers.add(value.userId());
            state.changedReservations.add(id);
            state.captureReservation(value);
        }
        reservations.clear();
        for (int index = 0; index < positions.size(); index++) {
            long id = positions.keyAt(index);
            PositionRuntime value = positions.valueAt(index);
            if (changedUsers != null && value != null) changedUsers.add(value.userId());
        }
        if (!removedOrderRoutes.isEmpty()) {
            if (changedOrders != null) removedOrderRoutes.forEach(changedOrders::add);
            removedOrderRoutes.clear();
        }
        if (!removedReservationRoutes.isEmpty()) {
            removedReservationRoutes.clear();
        }
        publicationPrepared = false;
    }

    void commitBatchAdmissionToOwner(TradingRuntimeState state, int laneId) {
        if (state == null || laneId < 0 || laneId >= state.accountLanes.length) {
            throw new IllegalArgumentException("invalid batch admission owner handoff");
        }
        publishTriggersToOwner(state);
        if (closedTriggerCount != 0) {
            state.revision = Math.addExact(state.revision, closedTriggerCount);
            closedTriggerCount = 0;
        }
        users.forEach((userId, ignored) -> state.changedUsers.add(userId));
        reservations.forEach((orderId, ignored) -> state.changedReservations.add(orderId));
        state.changedOrders.adopt(laneId, orders);
        state.changedPositions.adopt(laneId, positions);
        for (int index = 0; index < liquidations.size(); index++)
            state.changedLiquidations.put(liquidations.keyAt(index), liquidations.valueAt(index));
        for (int index = 0; index < riskSnapshots.size(); index++)
            state.changedRiskSnapshots.putIndexed(riskSnapshots.keyAt(index), riskSnapshots.valueAt(index),
                    riskSnapshots.hasPreparedAt(index), riskSnapshots.preparedAt(index));
        if (!removedOrderRoutes.isEmpty()) removedOrderRoutes.clear();
        if (!removedReservationRoutes.isEmpty()) removedReservationRoutes.clear();
    }

    void putAlgo(long id, com.surprising.aeron.service.state.model.CoreAlgoOrderState value) {
        if (algos == null) algos = new RuntimeChangeBuffer<>();
        algos.put(id, value);
    }

    void putTrigger(long id, CoreTriggerOrderState value) {
        if (triggers == null) triggers = new RuntimeChangeBuffer<>();
        triggers.put(id, value);
    }

    void publishTriggersToOwner(TradingRuntimeState state) {
        if (algos != null) algos.drainTo(state.changedAlgoOrders::put);
        if (triggers == null || triggers.isEmpty()) return;
        triggers.forEach((id, value) -> {
            state.changedTriggerOrders.put(id, value);
        });
        triggers.clear();
    }

    void recordControlChanges(TradingRuntimeState state, int laneId) {
        for (int index = 0; index < users.size(); index++) {
            state.changedUsers.add(users.keyAt(index));
            state.captureUserMetadata(users.valueAt(index));
        }
        for (int index = 0; index < reservations.size(); index++) {
            state.changedReservations.add(reservations.keyAt(index));
            state.captureReservation(reservations.valueAt(index));
        }
        state.changedOrders.adopt(laneId, orders);
        state.changedPositions.adopt(laneId, positions);
        for (int index = 0; index < liquidations.size(); index++)
            state.changedLiquidations.put(liquidations.keyAt(index), liquidations.valueAt(index));
        for (int index = 0; index < riskSnapshots.size(); index++)
            state.changedRiskSnapshots.putIndexed(riskSnapshots.keyAt(index), riskSnapshots.valueAt(index),
                    riskSnapshots.hasPreparedAt(index), riskSnapshots.preparedAt(index));
    }

    void clear() {
        closedTriggerCount = 0;
        java.util.Arrays.fill(retiredOrders, 0, retiredOrderCount, null);
        retiredOrderCount = 0;
        if (triggers != null) triggers.clear();
        if (algos != null) algos.clear();
        publicationPrepared = false;
        java.util.Arrays.fill(terminalOrderClients, 0, terminalOrderCount, null);
        // 原语列不持有引用；count 是唯一有效边界，recordTerminalOrder 先完整覆盖再递增。
        terminalOrderCount = 0;
        users.clear();
        orders.clear();
        reservations.clear();
        positions.clear();
        liquidations.clear();
        riskSnapshots.clear();
        if (!removedOrderRoutes.isEmpty()) removedOrderRoutes.clear();
        if (!removedReservationRoutes.isEmpty()) removedReservationRoutes.clear();
    }

}
