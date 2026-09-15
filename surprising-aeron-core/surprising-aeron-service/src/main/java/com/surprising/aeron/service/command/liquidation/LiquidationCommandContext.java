package com.surprising.aeron.service.command.liquidation;

import com.surprising.aeron.service.command.CommandResultContext;

/** Owner capabilities required by liquidation commands. */
public interface LiquidationCommandContext extends CommandResultContext {
    Iterable<Long> activeLiquidationIds();
}
