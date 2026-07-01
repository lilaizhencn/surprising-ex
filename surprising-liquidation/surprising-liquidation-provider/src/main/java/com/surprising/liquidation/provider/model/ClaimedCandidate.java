package com.surprising.liquidation.provider.model;

import com.surprising.trading.api.model.MarginMode;

public record ClaimedCandidate(
        long candidateId,
        long snapshotId,
        long userId,
        String symbol,
        MarginMode marginMode,
        long instrumentVersion,
        String settleAsset,
        long signedQuantitySteps,
        long markPriceTicks,
        long marginRatioPpm) {

    public ClaimedCandidate {
        marginMode = MarginMode.defaultIfNull(marginMode);
    }

    public ClaimedCandidate(long candidateId,
                            long snapshotId,
                            long userId,
                            String symbol,
                            long instrumentVersion,
                            String settleAsset,
                            long signedQuantitySteps,
                            long markPriceTicks,
                            long marginRatioPpm) {
        this(candidateId, snapshotId, userId, symbol, MarginMode.CROSS, instrumentVersion, settleAsset,
                signedQuantitySteps, markPriceTicks, marginRatioPpm);
    }
}
