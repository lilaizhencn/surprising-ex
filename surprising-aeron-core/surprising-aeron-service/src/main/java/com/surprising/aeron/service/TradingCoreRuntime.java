package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import com.surprising.aeron.service.state.OpenInterestIndex;
import com.surprising.aeron.service.state.AlgoOrderIndex;
import com.surprising.aeron.service.state.LiquidationIndex;
import com.surprising.aeron.service.state.CancelAllAfterIndex;
import com.surprising.aeron.service.state.ActiveOrderIndex;
import com.surprising.aeron.service.state.AdlPositionIndex;
import com.surprising.aeron.service.state.PositionUserIndex;
import com.surprising.aeron.service.state.RiskSnapshotIndex;
import com.surprising.aeron.service.state.TradingCoreReducer;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TriggerOrderIndex;
import com.surprising.product.api.ProductLine;

public final class TradingCoreRuntime implements AutoCloseable {
    private final ProductLine productLine;
    private final TradingCoreReducer reducer;
    private final DeterministicExchangeCoreAdapter matcher;
    private final PositionUserIndex positionUsers;
    private final OpenInterestIndex openInterest;
    private final TriggerOrderIndex triggers;
    private final AlgoOrderIndex algos;
    private final LiquidationIndex liquidations;
    private final CancelAllAfterIndex timers;
    private final ActiveOrderIndex activeOrders;
    private final AdlPositionIndex adlPositions;
    private final RiskSnapshotIndex riskSnapshots;
    private TradingCoreState state;
    private Thread owner;

    public TradingCoreRuntime(ProductLine productLine, TradingCoreState initialState) {
        if (productLine == null || initialState == null || initialState.productLine() != productLine) {
            throw new IllegalArgumentException("invalid trading runtime state");
        }
        this.productLine = productLine;
        this.state = initialState;
        this.reducer = new TradingCoreReducer();
        this.matcher = new DeterministicExchangeCoreAdapter();
        this.positionUsers = new PositionUserIndex(initialState);
        this.openInterest = new OpenInterestIndex(initialState);
        this.triggers = new TriggerOrderIndex(initialState);
        this.algos = new AlgoOrderIndex(initialState);
        this.liquidations = new LiquidationIndex(initialState);
        this.timers = new CancelAllAfterIndex(initialState);
        this.activeOrders = new ActiveOrderIndex(initialState);
        this.adlPositions = new AdlPositionIndex(initialState);
        this.riskSnapshots = new RiskSnapshotIndex(initialState);
        this.matcher.rebuild(initialState);
    }

    public void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) {
            owner = current;
        } else if (owner != current) {
            throw new IllegalStateException("trading runtime is bound to another thread");
        }
    }

    public void assertOwner() {
        bindOwner();
    }

    public TradingCoreState state() {
        assertOwner();
        return state;
    }

    public TradingCoreReducer reducer() {
        assertOwner();
        return reducer;
    }

    public DeterministicExchangeCoreAdapter matcher() {
        assertOwner();
        return matcher;
    }

    public PositionUserIndex positionUsers() {
        assertOwner();
        return positionUsers;
    }

    public OpenInterestIndex openInterest() {
        assertOwner();
        return openInterest;
    }

    public TriggerOrderIndex triggers() {
        assertOwner();
        return triggers;
    }

    public AlgoOrderIndex algos() {
        assertOwner();
        return algos;
    }

    public LiquidationIndex liquidations() {
        assertOwner();
        return liquidations;
    }

    public CancelAllAfterIndex timers() {
        assertOwner();
        return timers;
    }

    public ActiveOrderIndex activeOrders() {
        assertOwner();
        return activeOrders;
    }

    public AdlPositionIndex adlPositions() {
        assertOwner();
        return adlPositions;
    }

    public RiskSnapshotIndex riskSnapshots() {
        assertOwner();
        return riskSnapshots;
    }

    public void transition(TradingCoreState before, TradingCoreState after) {
        assertOwner();
        if (before != state || after == null || after.productLine() != productLine) {
            throw new IllegalStateException("trading runtime transition is out of order");
        }
        positionUsers.update(before, after);
        openInterest.update(before, after);
        triggers.update(before, after);
        algos.update(before, after);
        liquidations.update(before, after);
        timers.update(before, after);
        activeOrders.update(before, after);
        adlPositions.update(before, after);
        riskSnapshots.update(before, after);
        state = after;
    }

    public void restore(TradingCoreState restored) {
        assertOwner();
        if (restored == null || restored.productLine() != productLine) {
            throw new IllegalArgumentException("invalid restored trading state");
        }
        matcher.rebuild(restored);
        state = restored;
        positionUsers.rebuild(restored);
        openInterest.rebuild(restored);
        triggers.rebuild(restored);
        algos.rebuild(restored);
        liquidations.rebuild(restored);
        timers.rebuild(restored);
        activeOrders.rebuild(restored);
        adlPositions.rebuild(restored);
        riskSnapshots.rebuild(restored);
    }

    public void rebuildMatcher(TradingCoreState restored) {
        assertOwner();
        if (restored == null || restored.productLine() != productLine) {
            throw new IllegalArgumentException("invalid matcher state");
        }
        matcher.rebuild(restored);
    }

    @Override
    public void close() {
        matcher.close();
    }
}
