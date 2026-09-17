package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.service.state.index.AlgoOrderIndex;
import com.surprising.aeron.service.state.model.CoreAlgoOrderState;
import com.surprising.aeron.service.state.model.CoreOrderState;

import java.util.Map;

/** Owns immutable algo-order creation and revision-checked updates in the core state. */
final class AlgoOrderStateTransitions {

    private AlgoOrderStateTransitions() {
    }

    static TradingCoreState upsert(
            TradingCoreState state, long userId, CoreAlgoOrderView view) {
        return upsert(state, userId, view, null);
    }

    static TradingCoreState upsert(
            TradingCoreState state, long userId, CoreAlgoOrderView view, AlgoOrderIndex algoOrderIndex) {
        requireUserId(userId);
        if (view.userId() != userId) {
            throw new CoreStateRejectedException("ALGO_ORDER_OWNER_MISMATCH", "algo order belongs to another user");
        }
        if (view.clientAlgoOrderId().isBlank()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "clientAlgoOrderId is required");
        }
        CoreAlgoOrderState next = CoreAlgoOrderState.from(view);
        CoreAlgoOrderState current = state.algoOrders().get(next.algoOrderId());
        if (current == null) {
            boolean duplicateClient = algoOrderIndex != null
                    ? algoOrderIndex.containsClient(userId, next.clientAlgoOrderId())
                    : !next.clientAlgoOrderId().isEmpty() && state.algoOrders().values().stream()
                    .anyMatch(value -> value.userId() == userId
                            && value.clientAlgoOrderId().equals(next.clientAlgoOrderId()));
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
                throw new CoreStateRejectedException("STALE_ALGO_ORDER_REVISION",
                        "algo order revision is stale");
            }
            if (next.revision() != Math.incrementExact(current.revision())
                    || next.childOrderIds().size() < current.childOrderIds().size()
                    || !next.childOrderIds().subList(0, current.childOrderIds().size())
                    .equals(current.childOrderIds())
                    || next.childOrderIds().size() > current.childOrderIds().size() + 1) {
                throw new CoreStateRejectedException("INVALID_ALGO_ORDER_REVISION",
                        "algo order revision is not monotonic");
            }
            if (next.childOrderIds().size() > current.childOrderIds().size()) {
                long childOrderId = next.childOrderIds().getLast();
                CoreOrderState child = state.order(childOrderId);
                if (child == null || child.userId() != userId || !child.symbol().equals(next.symbol())) {
                    throw new CoreStateRejectedException("INVALID_ALGO_CHILD",
                            "algo child order is not authoritative");
                }
            }
        }
        Map<Long, CoreAlgoOrderState> values = StateMapSupport.delta(state.algoOrders());
        values.put(next.algoOrderId(), next);
        return new TradingCoreState(state.productLine(), Math.incrementExact(state.revision()),
                state.users(), state.orders(), state.instruments(), state.riskState(), state.treasuryState(),
                state.leverages(), values, state.cancelAllAfterTimers(), state.clientOrderIndex(),
                state.triggerOrders());
    }

    private static void requireSameIntent(CoreAlgoOrderState left, CoreAlgoOrderState right) {
        if (left.userId() != right.userId() || !left.clientAlgoOrderId().equals(right.clientAlgoOrderId())
                || !left.symbol().equals(right.symbol()) || left.algoTypeCode() != right.algoTypeCode()
                || left.side() != right.side() || left.priceTicks() != right.priceTicks()
                || left.quantitySteps() != right.quantitySteps() || left.childQuantitySteps() != right.childQuantitySteps()
                || left.intervalSeconds() != right.intervalSeconds() || left.durationSeconds() != right.durationSeconds()
                || left.marginMode() != right.marginMode() || left.positionSide() != right.positionSide()
                || left.reduceOnly() != right.reduceOnly() || left.postOnly() != right.postOnly()
                || left.timeInForce() != right.timeInForce() || left.startAtEpochMillis() != right.startAtEpochMillis()
                || left.createdAtEpochMillis() != right.createdAtEpochMillis()) {
            throw new CoreStateRejectedException("ALGO_ORDER_INTENT_MISMATCH", "algo order intent is immutable");
        }
    }

    private static void requireUserId(long userId) {
        if (userId <= 0) {
            throw new CoreStateRejectedException("INVALID_USER_ID", "userId must be positive");
        }
    }
}
