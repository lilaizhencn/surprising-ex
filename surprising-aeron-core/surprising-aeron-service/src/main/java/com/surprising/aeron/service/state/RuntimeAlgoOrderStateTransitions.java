package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.model.CoreAlgoOrderState;

/** Owns runtime algorithm-order identity, intent and child-order revision changes. */
public final class RuntimeAlgoOrderStateTransitions {

    private RuntimeAlgoOrderStateTransitions() {
    }

    public static void upsert(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                       long userId, CoreAlgoOrderView view) {
        if (runtime == null || identities == null || view == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime algo order update");
        }
        upsert(runtime, userId, view, identities.symbolId(view.symbol()));
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
                if (child == null || child.userId() != userId || child.symbolId() != symbolId) {
                    throw new CoreStateRejectedException("INVALID_ALGO_CHILD",
                            "algo child order is not authoritative");
                }
            }
        }
        runtime.putAlgoOrder(next);
        runtime.incrementCommandRevision();
    }

    private static void requireSameIntent(CoreAlgoOrderState left, CoreAlgoOrderState right) {
        if (left.userId() != right.userId() || !left.clientAlgoOrderId().equals(right.clientAlgoOrderId())
                || !left.symbol().equals(right.symbol()) || left.algoTypeCode() != right.algoTypeCode()
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
