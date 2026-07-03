package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PositionMarginAdjustmentRequest(
        @Positive long userId,
        @NotBlank @Size(max = 64) String symbol,
        MarginMode marginMode,
        PositionSide positionSide,
        long amountUnits,
        @NotBlank @Size(max = 128) String referenceId,
        @Size(max = 128) String reason) {

    public PositionMarginAdjustmentRequest {
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
    }

    public PositionMarginAdjustmentRequest(long userId,
                                           String symbol,
                                           MarginMode marginMode,
                                           long amountUnits,
                                           String referenceId,
                                           String reason) {
        this(userId, symbol, marginMode, PositionSide.NET, amountUnits, referenceId, reason);
    }
}
