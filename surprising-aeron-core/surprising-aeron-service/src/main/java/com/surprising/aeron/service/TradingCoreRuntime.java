package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import com.surprising.aeron.service.state.OpenInterestIndex;
import com.surprising.aeron.service.state.PositionUserIndex;
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

    public void transition(TradingCoreState before, TradingCoreState after) {
        assertOwner();
        if (before != state || after == null || after.productLine() != productLine) {
            throw new IllegalStateException("trading runtime transition is out of order");
        }
        positionUsers.update(before, after);
        openInterest.update(before, after);
        triggers.update(before, after);
        state = after;
    }

    public void restore(TradingCoreState restored) {
        assertOwner();
        if (restored == null || restored.productLine() != productLine) {
            throw new IllegalArgumentException("invalid restored trading state");
        }
        state = restored;
        positionUsers.rebuild(restored);
        openInterest.rebuild(restored);
        triggers.rebuild(restored);
    }

    @Override
    public void close() {
        matcher.close();
    }
}
