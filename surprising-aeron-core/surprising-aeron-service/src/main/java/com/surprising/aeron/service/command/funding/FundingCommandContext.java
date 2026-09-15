package com.surprising.aeron.service.command.funding;

import com.surprising.aeron.service.command.CommandResultContext;
import com.surprising.aeron.service.state.PositionUserIndex;

/** Owner capabilities required by perpetual funding commands. */
public interface FundingCommandContext extends CommandResultContext {
    PositionUserIndex positionUserIndex();
}
