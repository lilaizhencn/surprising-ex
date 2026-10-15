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
        // Avoid redundant List.copyOf if already empty or immutable list
        newestFirst = newestFirst.isEmpty() ? List.of() : newestFirst;
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
                    if (commitments == null) commitments = new ArrayList<>(4);
                    commitments.add(new Commitment(orderId, order.remainingQuantitySteps(), order.clusterPosition()));
                }
                requestedQuantity = Math.addExact(requestedQuantity, order.remainingQuantitySteps());
            }
        }
        if (commitments != null && commitments.size() > 1) {
            commitments.sort(NEWEST_FIRST);
        }
        long committed = Math.min(positionQuantity, requestedQuantity);
        List<Commitment> sortedCommitments;
        if (commitments == null || commitments.isEmpty()) {
            sortedCommitments = List.of();
        } else if (commitments.size() == 1) {
            sortedCommitments = List.of(commitments.get(0));
        } else {
            sortedCommitments = commitments;
        }
        return new PositionCloseCapacity(positionQuantity, requestedQuantity, committed,
                Math.subtractExact(positionQuantity, committed), sortedCommitments);
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
        if (overflow <= 0 || newestFirst.isEmpty()) return List.of();

        Long singleConflict = null;
        ArrayList<Long> conflicts = null;
        long released = 0;
        int size = newestFirst.size();
        for (int i = 0; i < size; i++) {
            Commitment commitment = newestFirst.get(i);
            if (singleConflict == null) {
                singleConflict = commitment.orderId();
            } else {
                if (conflicts == null) {
                    conflicts = new ArrayList<>(4);
                    conflicts.add(singleConflict);
                }
                conflicts.add(commitment.orderId());
            }
            released = Math.addExact(released, commitment.quantitySteps());
            if (released >= overflow) break;
        }
        if (conflicts == null) {
            return singleConflict == null ? List.of() : List.of(singleConflict);
        }
        return conflicts;
    }

    public record Commitment(long orderId, long quantitySteps, long corePosition) {
        public Commitment {
            if (orderId <= 0 || quantitySteps <= 0 || corePosition < 0) {
                throw new IllegalArgumentException("invalid close-capacity commitment");
            }
        }
    }
}
