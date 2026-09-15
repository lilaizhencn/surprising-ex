package com.surprising.aeron.service.command.fee;

import com.surprising.aeron.service.command.CommandResultContext;

/** Owner capabilities required by fee-policy configuration commands. */
public interface FeePolicyCommandContext extends CommandResultContext {
    void refreshFeePolicyHash();
}
