package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.index.AlgoOrderIndex;
import com.surprising.aeron.service.state.index.CancelAllAfterIndex;
import com.surprising.aeron.service.state.index.LiquidationIndex;
import com.surprising.aeron.service.state.index.TriggerOrderIndex;

import com.surprising.aeron.service.state.model.CoreAlgoOrderState;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterKey;
import com.surprising.aeron.service.state.model.CoreCancelAllAfterState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;

public final class RuntimeFactIndexes implements RuntimeFactFrame.ChangeConsumer {
    private final PositionUserIndex positionUsers;
    private final OpenInterestIndex openInterest;
    private final TriggerOrderIndex triggers;
    private final AlgoOrderIndex algos;
    private final LiquidationIndex liquidations;
    private final CancelAllAfterIndex timers;
    private final ActiveOrderIndex activeOrders;
    private final AdlPositionIndex adlPositions;
    private RuntimeFactFrame.IdentityView activeIdentities;

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

    public void applyCurrent(TradingRuntimeState runtime, RuntimeFactFrame.IdentityView identities) {
        if (runtime == null || identities == null || activeIdentities != null) {
            throw new IllegalArgumentException("runtime changed indexes are invalid");
        }
        activeIdentities = identities;
        try {
            runtime.visitPreparedMatcherIndexes(this);
            runtime.visitChangedIndexes(this);
        } finally {
            activeIdentities = null;
        }
    }

    void preparedOrder(long orderId, CoreOrderState current) {
        activeOrders.applySnapshot(orderId, current);
    }

    void preparedPosition(long positionKey, RuntimePositionIndexValue current) {
        RuntimePositionIndexValue previous = adlPositions.value(positionKey);
        positionUsers.apply(previous, current);
        openInterest.apply(previous, current);
        adlPositions.apply(positionKey, previous, current);
    }

    @Override
    public void order(long orderId, OrderRuntime before, OrderRuntime after) {
        activeOrders.apply(orderId, after, activeIdentities);
    }

    @Override
    public void position(long positionKey, PositionRuntime before, PositionRuntime after) {
        RuntimePositionIndexValue previous = adlPositions.value(positionKey);
        RuntimePositionIndexValue current = after == null
                ? null : RuntimePositionIndexValue.from(after, activeIdentities);
        positionUsers.apply(previous, current);
        openInterest.apply(previous, current);
        adlPositions.apply(positionKey, previous, current);
    }

    @Override
    public void liquidation(long liquidationId, LiquidationRuntime before, LiquidationRuntime after) {
        liquidations.apply(liquidationId, after, activeIdentities);
    }

    @Override
    public void algoOrder(long algoOrderId, CoreAlgoOrderState before, CoreAlgoOrderState after) {
        algos.apply(algoOrderId, after);
    }

    @Override
    public void triggerOrder(long triggerOrderId, CoreTriggerOrderState before,
                             CoreTriggerOrderState after) {
        triggers.apply(triggerOrderId, after);
    }

    @Override
    public void timer(CoreCancelAllAfterKey key, CoreCancelAllAfterState before,
                      CoreCancelAllAfterState after) {
        timers.apply(key, after);
    }

    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        positionUsers.rebuild(state, identities);
        openInterest.rebuild(state, identities);
        triggers.rebuild(state);
        algos.rebuild(state);
        liquidations.rebuild(state);
        timers.rebuild(state);
        activeOrders.rebuild(state, identities);
        adlPositions.rebuild(state, identities);
    }

    private static <T> T require(T index, String name) {
        if (index == null) throw new IllegalArgumentException(name + " index is required");
        return index;
    }

}
