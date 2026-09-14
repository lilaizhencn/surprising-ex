package com.surprising.aeron.service.command;

import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.TradingRuntimeState;

/** Narrow owner capabilities exposed to command handlers. */
public interface CommandOwnerContext {
    TradingRuntimeState runtimeState();
    RuntimeIdentityRegistry identities();
    void requestCommitPublication();
}
