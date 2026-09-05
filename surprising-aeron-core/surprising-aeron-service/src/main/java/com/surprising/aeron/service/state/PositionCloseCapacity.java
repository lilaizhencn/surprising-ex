package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public record PositionCloseCapacity(
        long positionQuantitySteps,
        long requestedQuantitySteps,
        long committedQuantitySteps,
        long availableQuantitySteps,
        List<Commitment> newestFirst) {

    public PositionCloseCapacity {
        if (positionQuantitySteps < 0 || requestedQuantitySteps < 0
                || committedQuantitySteps < 0 || availableQuantitySteps < 0
                || Math.addExact(committedQuantitySteps, availableQuantitySteps) != positionQuantitySteps
                || newestFirst == null) {
            throw new IllegalArgumentException("invalid position close capacity");
        }
        newestFirst = List.copyOf(newestFirst);
    }

    public static PositionCloseCapacity inspect(
            TradingCoreState state,
            CoreUserState user,
            String symbol,
            CorePositionSide positionSide,
            CoreOrderSide closeSide,
            ActiveOrderIndex activeOrderIndex) {
        return inspect(state, user, symbol, positionSide, closeSide, activeOrderIndex, 0);
    }

    public static PositionCloseCapacity inspect(
            TradingCoreState state,
            CoreUserState user,
            String symbol,
            CorePositionSide positionSide,
            CoreOrderSide closeSide,
            ActiveOrderIndex activeOrderIndex,
            long excludedOrderId) {
        if (state == null || user == null || positionSide == null || closeSide == null) {
            throw new IllegalArgumentException("position close capacity input is required");
        }
        String normalizedSymbol = OrderReservation.normalizeSymbol(symbol);
        CorePositionState position = user.positions().get(positionKey(normalizedSymbol, positionSide));
        long positionQuantity = position == null ? 0 : Math.absExact(position.signedQuantitySteps());
        long requestedQuantity = 0;
        ArrayList<Commitment> commitments = new ArrayList<>();
        Iterable<Long> orderIds = activeOrderIndex == null
                ? state.orders().keySet() : activeOrderIndex.ids(user.userId(), normalizedSymbol);
        for (Long orderId : orderIds) {
            if (orderId == null || orderId == excludedOrderId) continue;
            CoreOrderState order = state.order(orderId);
            if (order == null || order.status() != CoreOrderStatus.OPEN
                    || order.userId() != user.userId() || !order.symbol().equals(normalizedSymbol)
                    || order.positionSide() != positionSide || order.side() != closeSide) {
                continue;
            }
            if (order.reduceOnly()) {
                commitments.add(new Commitment(order.orderId(), order.remainingQuantitySteps(),
                        order.clusterPosition()));
            }
            requestedQuantity = Math.addExact(requestedQuantity, order.remainingQuantitySteps());
        }
        commitments.sort(Comparator.comparingLong(Commitment::corePosition)
                .thenComparingLong(Commitment::orderId).reversed());
        long committed = Math.min(positionQuantity, requestedQuantity);
        return new PositionCloseCapacity(positionQuantity, requestedQuantity, committed,
                Math.subtractExact(positionQuantity, committed), commitments);
    }

    public static PositionCloseCapacity inspectRuntime(
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, long userId, String symbol,
            CorePositionSide positionSide, CoreOrderSide closeSide, ActiveOrderIndex activeOrderIndex,
            long excludedOrderId) {
        if (runtime == null || identities == null || userId <= 0 || positionSide == null
                || closeSide == null || activeOrderIndex == null) {
            throw new IllegalArgumentException("runtime close-capacity input is required");
        }
        String normalizedSymbol = OrderReservation.normalizeSymbol(symbol);
        String positionName = positionSide == CorePositionSide.NET
                ? normalizedSymbol : normalizedSymbol + ':' + positionSide.name();
        Long positionKey = identities.findPositionKey(userId, positionName);
        PositionRuntime position = positionKey == null ? null : runtime.position(positionKey);
        long positionQuantity = position == null ? 0 : Math.absExact(position.signedQuantitySteps());
        long requestedQuantity = 0;
        ArrayList<Commitment> commitments = new ArrayList<>();
        for (Long orderId : activeOrderIndex.ids(userId, normalizedSymbol)) {
            if (orderId == null || orderId == excludedOrderId) continue;
            OrderRuntime order = runtime.order(orderId);
            if (order == null || order.status() != CoreOrderStatus.OPEN || order.userId() != userId
                    || !identities.symbol(order.symbolId()).equals(normalizedSymbol)
                    || order.positionSide() != positionSide || order.side() != closeSide) continue;
            if (order.reduceOnly()) {
                commitments.add(new Commitment(order.orderId(), order.remainingQuantitySteps(),
                        order.clusterPosition()));
            }
            requestedQuantity = Math.addExact(requestedQuantity, order.remainingQuantitySteps());
        }
        commitments.sort(Comparator.comparingLong(Commitment::corePosition)
                .thenComparingLong(Commitment::orderId).reversed());
        long committed = Math.min(positionQuantity, requestedQuantity);
        return new PositionCloseCapacity(positionQuantity, requestedQuantity, committed,
                Math.subtractExact(positionQuantity, committed), commitments);
    }

    public void require(long quantitySteps) {
        if (quantitySteps <= 0) throw new IllegalArgumentException("close quantity must be positive");
        if (quantitySteps > availableQuantitySteps) {
            throw new CoreStateRejectedException("REDUCE_ONLY_CAPACITY_EXCEEDED",
                    "reduce-only open quantity exceeds position capacity");
        }
    }

    public List<Long> conflictsFor(long incomingQuantitySteps) {
        if (incomingQuantitySteps <= 0) {
            throw new IllegalArgumentException("incoming close quantity must be positive");
        }
        long overflow = Math.subtractExact(Math.addExact(requestedQuantitySteps, incomingQuantitySteps),
                positionQuantitySteps);
        if (overflow <= 0) return List.of();
        ArrayList<Long> conflicts = new ArrayList<>();
        long released = 0;
        for (Commitment commitment : newestFirst) {
            conflicts.add(commitment.orderId());
            released = Math.addExact(released, commitment.quantitySteps());
            if (released >= overflow) break;
        }
        return List.copyOf(conflicts);
    }

    private static String positionKey(String symbol, CorePositionSide positionSide) {
        return positionSide == CorePositionSide.NET ? symbol : symbol + ':' + positionSide.name();
    }

    public record Commitment(long orderId, long quantitySteps, long corePosition) {
        public Commitment {
            if (orderId <= 0 || quantitySteps <= 0 || corePosition < 0) {
                throw new IllegalArgumentException("invalid close-capacity commitment");
            }
        }
    }
}
