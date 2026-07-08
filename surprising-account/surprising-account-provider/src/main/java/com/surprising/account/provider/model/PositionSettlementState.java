package com.surprising.account.provider.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;

public record PositionSettlementState(
        long userId,
        String symbol,
        MarginMode marginMode,
        PositionSide positionSide,
        PositionState state,
        Instant updatedAt) {

    public PositionSettlementState {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public long instrumentVersion() {
        return state.instrumentVersion();
    }

    public long signedQuantitySteps() {
        return state.signedQuantitySteps();
    }

    public long entryPriceTicks() {
        return state.entryPriceTicks();
    }

    public long realizedPnlUnits() {
        return state.realizedPnlUnits();
    }
}
