package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.account.UserRuntime;
import com.surprising.aeron.service.state.risk.RiskSnapshotRuntime;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;

public final class LaneCommitDelta {
    /** 本 Lane 的实体键与原语 after-image；Owner 原地更新自己的独立镜像。 */
    private boolean publicationPrepared;
    /** Lane 准备的终态原语收据；Owner 不再重新遍历订单或创建 OrderRuntime 快照。 */
    private long[] terminalOrderIds = new long[4];
    private long[] terminalOrderUsers = new long[4];
    private String[] terminalOrderClients = new String[4];
    private int terminalOrderCount;

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
        orders.capturePublicationValues();
        reservations.capturePublicationValues();
        if (terminalOrderCount == 0) {
            orders.forEach((orderId, order) -> {
                if (order != null && order.status().terminal()) recordTerminalOrder(order);
            });
        }
        publicationPrepared = true;
    }

    /** Returns terminal identity data already collected by the Lane; only indices below count are valid. */
    public int terminalOrderCount() { return terminalOrderCount; }
    public long terminalOrderId(int index) { return terminalOrderIds[index]; }
    public long terminalOrderUser(int index) { return terminalOrderUsers[index]; }
    public String terminalOrderClient(int index) { return terminalOrderClients[index]; }

    /** 本次需要发布的触发单变化。 */
    RuntimeChangeBuffer<CoreTriggerOrderState> triggers;
    int closedTriggerCount;
    /** 本次需要发布的用户变化。 */
    final RuntimeChangeBuffer<UserRuntime> users = new RuntimeChangeBuffer<>();
    /** 本次需要发布的订单变化。 */
    final OrderChangeBuffer orders = new OrderChangeBuffer();
    /** 本次需要发布的预留变化。 */
    final ReservationChangeBuffer reservations = new ReservationChangeBuffer();
    /** 本次需要发布的持仓变化。 */
    final RuntimeIndexedChangeBuffer<PositionRuntime, RuntimePositionIndexValue> positions = new RuntimeIndexedChangeBuffer<>();
    /** 本次需要发布的清算变化。 */
    final RuntimeChangeBuffer<LiquidationRuntime> liquidations = new RuntimeChangeBuffer<>();
    /** 本次需要发布的风险快照变化。 */
    final RuntimeChangeBuffer<RiskSnapshotRuntime> riskSnapshots = new RuntimeChangeBuffer<>();
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
        orders.put(key, value);
    }

    void putReservation(long key, ReservationRuntime value) {
        reservations.put(key, value);
    }

    void putPosition(long key, PositionRuntime value) {
        positions.put(key, value);
    }

    void putLiquidation(long key, LiquidationRuntime value) {
        liquidations.put(key, value);
    }

    void putRiskSnapshot(long key, RiskSnapshotRuntime value) {
        riskSnapshots.put(key, value);
    }

    void removeOrderRoute(long orderId) { removedOrderRoutes.add(orderId); }
    void removeReservationRoute(long orderId) { removedReservationRoutes.add(orderId); }

    void drainTo(int laneId,
                         LanePublishedMap<UserRuntime> targetUsers,
                         LanePublishedMap<OrderRuntime> targetOrders,
                         LanePublishedMap<ReservationRuntime> targetReservations,
                         LanePublishedMap<PositionRuntime> targetPositions,
                         LongObjectHashMap<LiquidationRuntime> targetLiquidations,
                         LongObjectHashMap<RiskSnapshotRuntime> targetRiskSnapshots,
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
        users.drainToPublishedMap(targetUsers);
        orders.drainToPublishedMap(targetOrders);
        reservations.drainToPublishedMap(targetReservations);
        positions.drainToPublishedMap(targetPositions);
        liquidations.drainToEclipseMap(targetLiquidations);
        riskSnapshots.drainToEclipseMap(targetRiskSnapshots);
        removedOrderRoutes.forEach(orderId -> {
            targetOrders.remove(orderId);

        });
        removedReservationRoutes.forEach(orderId -> {
            targetReservations.remove(orderId);

        });
        if (!removedOrderRoutes.isEmpty()) removedOrderRoutes.clear();
        if (!removedReservationRoutes.isEmpty()) removedReservationRoutes.clear();
    }

    void commitTerminalToOwner(TradingRuntimeState state, int laneId,
                                       TradingRuntimeState.TerminalOrderSink terminalOrderSink, long coreSequence,
                                       com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedUsers,
                                       com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedOrders) {
        OwnerSettlementMergeEvent timing = OwnerSettlementMergeEvent.sample(coreSequence, "lane", laneId);
        publishTriggersToOwner(state);
        if (closedTriggerCount != 0) {
            state.revision = Math.addExact(state.revision, closedTriggerCount);
            closedTriggerCount = 0;
        }
        if (!publicationPrepared) throw new IllegalStateException("Lane terminal publication is not prepared");
        long started = timing == null ? 0 : System.nanoTime();
        publishPreparedToOwner(state, changedUsers, changedOrders, timing);
        if (timing != null) timing.publicationNanos = System.nanoTime() - started;
        if (timing != null) { timing.terminalOrders = terminalOrderCount; started = System.nanoTime(); }
        if (terminalOrderSink != null && terminalOrderCount != 0)
            terminalOrderSink.acceptBatch(this, coreSequence);
        if (timing != null) timing.terminalIndexNanos = System.nanoTime() - started;
        liquidations.drainTo((id, value) -> {
            state.changedLiquidations.put(id, value);
            TradingRuntimeState.putOrRemove(state.publishedLiquidations, id, value);
        });
        riskSnapshots.drainTo((key, value) -> {
            state.changedRiskSnapshots.put(key, value);
            TradingRuntimeState.putOrRemove(state.publishedRiskSnapshots, key, value);
        });
        if (timing != null) started = System.nanoTime();
        state.changedOrders.adopt(laneId, orders);
        state.changedPositions.adopt(laneId, positions);
        if (timing != null) {
            timing.changedIndexNanos = System.nanoTime() - started;
            timing.completed = true;
            timing.finish();
        }
    }

    private void publishPreparedToOwner(
            TradingRuntimeState state,
            com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedUsers,
            com.surprising.aeron.service.command.support.PrimitiveLongChangeSet changedOrders,
            OwnerSettlementMergeEvent timing) {
        long started = timing == null ? 0 : System.nanoTime();
        users.drainTo((id, value) -> {
            if (changedUsers != null) changedUsers.add(id);
            state.publishedUsers.applyPublished(id, value);
            state.changedUsers.add(id);
        });
        if (timing != null) { timing.usersNanos = System.nanoTime() - started; started = System.nanoTime(); }
        for (int index = 0; index < orders.size(); index++) {
            long id = orders.keyAt(index);
            if (changedOrders != null) changedOrders.add(id);
            if (timing != null && timing.mapTiming) timing.ordersVisited++;
            if (!removedOrderRoutes.contains(id))
                orders.applyPublished(index, state.publishedOrders,
                        timing != null && timing.mapTiming ? timing : null);
            else if (timing != null && timing.mapTiming) timing.ordersSkipped++;
        }
        if (timing != null) { timing.ordersNanos = System.nanoTime() - started; started = System.nanoTime(); }
        for (int index = 0; index < reservations.size(); index++) {
            long id = reservations.keyAt(index);
            ReservationRuntime value = reservations.valueAt(index);
            if (changedOrders != null) changedOrders.add(id);
            if (changedUsers != null && value != null) changedUsers.add(value.userId());
            if (!removedReservationRoutes.contains(id))
                reservations.applyPublished(index, state.publishedReservations);
            state.changedReservations.add(id);
        }
        reservations.clear();
        if (timing != null) { timing.reservationsNanos = System.nanoTime() - started; started = System.nanoTime(); }
        for (int index = 0; index < positions.size(); index++) {
            long id = positions.keyAt(index);
            PositionRuntime value = positions.valueAt(index);
            if (changedUsers != null && value != null) changedUsers.add(value.userId());
            if (value == null && state.realtimeCapture != null) {
                try { state.realtimeCapture.removedPosition(state.publishedPositions.get(id)); }
                catch (RuntimeException failure) { state.realtimeCapture.failed(); }
            }
            if (value == null) {
                state.publishedPositions.applyPublished(id, null);
                continue;
            }
            PositionRuntime published = state.publishedPositions.get(id);
            if (published == null) {
                published = value.publicationValue();
                state.publishedPositions.put(id, published);
            } else {
                published.copyStateFrom(value);
            }
            positions.setValueAt(index, published);
        }
        if (timing != null) { timing.positionsNanos = System.nanoTime() - started; started = System.nanoTime(); }
        removedOrderRoutes.forEach(id -> {
            state.publishedOrders.removePublished(id, timing, false);
            if (changedOrders != null) changedOrders.add(id);
        });
        removedReservationRoutes.forEach(id -> state.publishedReservations.removePublished(id, timing, true));
        removedOrderRoutes.clear();
        removedReservationRoutes.clear();
        publicationPrepared = false;
        if (timing != null) timing.removalsNanos = System.nanoTime() - started;
    }

    /**
     * Commit the marker side of a batch admission whose immutable publication was already
     * applied by the Owner before Matcher submission. Admission directly mutates the private
     * Lane maps and stages its public user/order/reservation after-images in the event
     * publication, so replaying the normal terminal drain would duplicate the map walk.
     * Balance and funds patches remain consumed by collectPlaceBatchAdmission.
     */
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
        liquidations.forEach(state.changedLiquidations::put);
        riskSnapshots.forEach(state.changedRiskSnapshots::put);
        removedOrderRoutes.forEach(orderId -> state.publishedOrders.remove(orderId));
        removedReservationRoutes.forEach(orderId -> state.publishedReservations.remove(orderId));
        if (!removedOrderRoutes.isEmpty()) removedOrderRoutes.clear();
        if (!removedReservationRoutes.isEmpty()) removedReservationRoutes.clear();
    }

    void putTrigger(long id, CoreTriggerOrderState value) {
        if (triggers == null) triggers = new RuntimeChangeBuffer<>();
        triggers.put(id, value);
    }

    void publishTriggersToOwner(TradingRuntimeState state) {
        if (triggers == null || triggers.isEmpty()) return;
        triggers.forEach((id, value) -> {
            TradingRuntimeState.putOrRemove(state.publishedTriggerOrders, id, value);
            state.changedTriggerOrders.put(id, value);
        });
        triggers.clear();
    }

    void recordRiskChanges(TradingRuntimeState state) {
        liquidations.forEach(state.changedLiquidations::put);
        riskSnapshots.forEach(state.changedRiskSnapshots::put);
    }

    void clear() {
        closedTriggerCount = 0;
        if (triggers != null) triggers.clear();
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
