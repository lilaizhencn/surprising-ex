package com.surprising.aeron.service.state.position;

import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.state.model.CorePositionState;

public record RuntimePositionIndexValue(long userId, String instrumentId, String asset,
                                 CorePositionSide positionSide, long signedQuantitySteps) {

    public static RuntimePositionIndexValue from(long userId, CorePositionState position) {
        return new RuntimePositionIndexValue(userId, position.instrumentId(), position.marginAsset(),
                position.positionSide(), position.signedQuantitySteps());
    }

}
