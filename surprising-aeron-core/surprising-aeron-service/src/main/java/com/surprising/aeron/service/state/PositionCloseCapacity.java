package com.surprising.aeron.service.state;

import com.surprising.aeron.service.exception.CoreStateRejectedException;

import com.surprising.aeron.service.state.model.CoreOrderStatus;

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

    private static final Comparator<Commitment> NEWEST_FIRST = Comparator.comparingLong(Commitment::corePosition)
            .thenComparingLong(Commitment::orderId).reversed();

    public PositionCloseCapacity {
        if (positionQuantitySteps < 0 || requestedQuantitySteps < 0
                || committedQuantitySteps < 0 || availableQuantitySteps < 0
                || Math.addExact(committedQuantitySteps, availableQuantitySteps) != positionQuantitySteps
                || newestFirst == null) {
            throw new IllegalArgumentException("invalid position close capacity");
        }
        newestFirst = List.copyOf(newestFirst);
    }

    /** Account Lane inspects its own live orders, excluding uncommitted reservations. */
    static PositionCloseCapacity inspectLane(AccountLaneState lane, long userId, int symbolId,
            long positionKey, CorePositionSide positionSide, CoreOrderSide closeSide, long excludedOrderId) {
        PositionRuntime position = lane.positions.get(positionKey);
        long positionQuantity = position == null ? 0 : Math.absExact(position.signedQuantitySteps());
        long requestedQuantity = 0;
        ArrayList<Commitment> commitments = null;
        var ids = lane.activeOrderIdsByUser.get(userId);
        if (ids != null) {
            var iterator = ids.iterator();
            while (iterator.hasNext()) {
                long orderId = iterator.nextValue();
                if (orderId == excludedOrderId || lane.pendingReservationSequences.containsKey(orderId)) continue;
                OrderRuntime order = lane.orders.get(orderId);
                if (order == null || order.status() != CoreOrderStatus.OPEN || order.userId() != userId
                        || order.symbolId() != symbolId || order.positionSide() != positionSide
                        || order.side() != closeSide) continue;
                if (order.reduceOnly()) {
                    if (commitments == null) commitments = new ArrayList<>();
                    commitments.add(new Commitment(orderId, order.remainingQuantitySteps(), order.clusterPosition()));
                }
                requestedQuantity = Math.addExact(requestedQuantity, order.remainingQuantitySteps());
            }
        }
        if (commitments != null) commitments.sort(NEWEST_FIRST);
        long committed = Math.min(positionQuantity, requestedQuantity);
        return new PositionCloseCapacity(positionQuantity, requestedQuantity, committed,
                Math.subtractExact(positionQuantity, committed), commitments == null ? List.of() : commitments);
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

    public record Commitment(long orderId, long quantitySteps, long corePosition) {
        public Commitment {
            if (orderId <= 0 || quantitySteps <= 0 || corePosition < 0) {
                throw new IllegalArgumentException("invalid close-capacity commitment");
            }
        }
    }
}
