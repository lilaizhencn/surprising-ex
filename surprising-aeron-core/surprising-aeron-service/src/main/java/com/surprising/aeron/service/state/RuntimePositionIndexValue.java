package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CorePositionState;

import com.surprising.aeron.protocol.CorePositionSide;

record RuntimePositionIndexValue(long userId, String instrumentId, String asset,
                                 CorePositionSide positionSide, long signedQuantitySteps) {

    static RuntimePositionIndexValue from(long userId, CorePositionState position) {
        return new RuntimePositionIndexValue(userId, position.instrumentId(), position.marginAsset(),
                position.positionSide(), position.signedQuantitySteps());
    }

    static RuntimePositionIndexValue from(PositionRuntime position, RuntimeIdentityRegistry identities) {
        return new RuntimePositionIndexValue(position.userId(), identities.instrumentId(position.symbolId()),
                identities.asset(position.assetId()), position.positionSide(), position.signedQuantitySteps());
    }
}
