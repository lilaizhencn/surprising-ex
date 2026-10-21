package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.model.CoreAlgoOrderState;

/** Owns runtime algorithm-order identity, intent and child-order revision changes. */
public final class RuntimeAlgoOrderStateTransitions {

    private RuntimeAlgoOrderStateTransitions() {
    }

    /** Child admission shares the parent's Account Lane and cancellation cursor. */
    static void requireChildAdmission(AccountLaneState lane, long userId, ResolvedPlaceOrder child) {
        String key = child.clientOrderId();
        if (key == null || !key.startsWith("algo-")) return;
        int separator = key.lastIndexOf('-');
        long id; int slice;
        try {
            id = Long.parseLong(key.substring(5, separator));
            slice = Integer.parseInt(key.substring(separator + 1));
        } catch (RuntimeException invalid) {
            throw new CoreStateRejectedException("INVALID_ALGO_CHILD", "invalid algo child identity");
        }
        var parent = lane.cold.algoOrders.get(id);
        if (parent == null || !("algo-" + id + "-" + slice).equals(key)
                || parent.userId() != userId || parent.statusCode() >= 2
                || !parent.instrumentId().equals(child.instrumentId()) || parent.side() != child.side()
                || slice != parent.childOrderIds().size() || parent.pendingClosedChildId() != 0
                || child.quantitySteps() > parent.childQuantitySteps()
                || child.quantitySteps() > parent.quantitySteps() - parent.retiredExecutedQuantitySteps()) {
            throw new CoreStateRejectedException("INVALID_ALGO_CHILD", "algo parent cannot admit this child");
        }
        var active = lane.orders.get(parent.currentOrderId());
        if (active != null && !active.status().terminal())
            throw new CoreStateRejectedException("INVALID_ALGO_CHILD", "algo child is still active");
    }

    /** Fold the execution into its parent on the same settlement Lane before recycling the child. */
    static void retireChild(AccountLaneState lane, RuntimeIdentityRegistry identities,
                            OrderRuntime child, LaneCommitDelta changes) {
        lane.assertOwner();
        String client = child.clientOrderId();
        if (client == null || !client.startsWith("algo-")) return;
        int separator = client.lastIndexOf('-');
        if (separator <= 5) return;
        final long parentId;
        final int slice;
        try {
            parentId = Long.parseLong(client.substring(5, separator));
            slice = Integer.parseInt(client.substring(separator + 1));
        } catch (NumberFormatException malformed) { return; }
        var parent = lane.cold.algoOrders.get(parentId);
        if (parent == null || parent.userId() != child.userId() || slice < 0
                || !parent.instrumentId().equals(identities.instrumentId(child.symbolId()))) return;
        boolean linked = slice < parent.childOrderIds().size() && parent.childOrderIds().get(slice) == child.orderId();
        boolean pending = slice == parent.childOrderIds().size()
                && (parent.pendingClosedChildId() == 0 || parent.pendingClosedChildId() == child.orderId());
        if ((!linked && !pending) || child.executedQuantitySteps() > parent.quantitySteps() - parent.retiredExecutedQuantitySteps()) return;
        long executed = Math.addExact(parent.retiredExecutedQuantitySteps(), child.executedQuantitySteps());
        var updated = parent.withRetiredProgress(executed, pending ? child.orderId() : parent.pendingClosedChildId());
        lane.cold.algoOrders.put(updated.algoOrderId(), updated);
        changes.putAlgo(updated.algoOrderId(), updated);
    }

    public static void upsert(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                       long userId, CoreAlgoOrderView view) {
        if (runtime == null || identities == null || view == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime algo order update");
        }
        upsert(runtime, userId, view, identities.symbolId(view.instrumentId()));
    }

    public static void upsert(TradingRuntimeState runtime, long userId,
                       CoreAlgoOrderView view, int symbolId) {
        if (runtime == null || view == null || userId <= 0 || symbolId < 0)
            throw new IllegalArgumentException("invalid prepared algo update");
        runtime.assertOwner();
        if (view.userId() != userId) {
            throw new CoreStateRejectedException("ALGO_ORDER_OWNER_MISMATCH", "algo order belongs to another user");
        }
        if (view.clientAlgoOrderId().isBlank()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "clientAlgoOrderId is required");
        }
        CoreAlgoOrderState next = CoreAlgoOrderState.from(view);
        CoreAlgoOrderState current = runtime.algoOrder(next.algoOrderId());
        if (current == null) {
            boolean duplicateClient = runtime.hasAlgoClient(userId, next.clientAlgoOrderId());
            if (duplicateClient) {
                throw new CoreStateRejectedException("DUPLICATE_CLIENT_ALGO_ORDER_ID",
                        "clientAlgoOrderId already exists");
            }
            if (!next.childOrderIds().isEmpty() || next.revision() != 1) {
                throw new CoreStateRejectedException("INVALID_ALGO_ORDER_CREATE", "new algo order must start empty");
            }
        } else {
            requireSameIntent(current, next);
            if (current.terminal() || current.statusCode() == 2 && next.statusCode() < 2) {
                throw new CoreStateRejectedException("INVALID_ALGO_ORDER_REVISION",
                        "terminal or cancel-requested algo cannot resume execution");
            }
            if (next.revision() <= current.revision()) {
                throw new CoreStateRejectedException("STALE_ALGO_ORDER_REVISION", "algo order revision is stale");
            }
            if (next.revision() != Math.incrementExact(current.revision())
                    || next.childOrderIds().size() < current.childOrderIds().size()
                    || !next.childOrderIds().subList(0, current.childOrderIds().size()).equals(current.childOrderIds())
                    || next.childOrderIds().size() > current.childOrderIds().size() + 1) {
                throw new CoreStateRejectedException("INVALID_ALGO_ORDER_REVISION",
                        "algo order revision is not monotonic");
            }
            if (next.childOrderIds().size() > current.childOrderIds().size()) {
                OrderRuntime child = runtime.order(next.childOrderIds().getLast());
                if ((child == null && current.pendingClosedChildId() != next.childOrderIds().getLast())
                        || (child != null && (child.userId() != userId || child.symbolId() != symbolId
                        || !("algo-"+current.algoOrderId()+"-"+current.childOrderIds().size()).equals(child.clientOrderId())))) {
                    throw new CoreStateRejectedException("INVALID_ALGO_CHILD",
                            "algo child order is not authoritative");
                }
            }
        }
        if (current != null) {
            long pending = current.pendingClosedChildId();
            if (next.childOrderIds().size() > current.childOrderIds().size()
                    && next.childOrderIds().getLast() == pending) pending = 0;
            next = next.withRetiredProgress(current.retiredExecutedQuantitySteps(), pending);
        }
        runtime.requireRevisionCapacity();
        runtime.putAlgoOrder(next);
        runtime.incrementCommandRevision();
    }

    private static void requireSameIntent(CoreAlgoOrderState left, CoreAlgoOrderState right) {
        if (left.userId() != right.userId() || !left.clientAlgoOrderId().equals(right.clientAlgoOrderId())
                || !left.instrumentId().equals(right.instrumentId()) || left.algoTypeCode() != right.algoTypeCode()
                || left.side() != right.side() || left.priceTicks() != right.priceTicks()
                || left.quantitySteps() != right.quantitySteps()
                || left.childQuantitySteps() != right.childQuantitySteps()
                || left.intervalSeconds() != right.intervalSeconds() || left.durationSeconds() != right.durationSeconds()
                || left.marginMode() != right.marginMode() || left.positionSide() != right.positionSide()
                || left.reduceOnly() != right.reduceOnly() || left.postOnly() != right.postOnly()
                || left.timeInForce() != right.timeInForce() || left.startAtEpochMillis() != right.startAtEpochMillis()
                || left.createdAtEpochMillis() != right.createdAtEpochMillis()) {
            throw new CoreStateRejectedException("ALGO_ORDER_INTENT_MISMATCH", "algo order intent is immutable");
        }
    }
}
