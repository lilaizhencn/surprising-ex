package com.surprising.account.api.model;

import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;

public record OrderReserveAccountCommand(
        long orderId,
        String symbol,
        OrderSide side,
        OrderReservationKind reservationKind,
        AccountType accountType,
        String asset,
        MarginMode marginMode,
        PositionSide positionSide,
        long reservedUnits) {

    public OrderReserveAccountCommand {
        if (orderId <= 0 || symbol == null || symbol.isBlank() || side == null || reservationKind == null
                || asset == null || asset.isBlank() || reservedUnits <= 0) {
            throw new IllegalArgumentException("invalid order reservation command");
        }
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        if (reservationKind == OrderReservationKind.DERIVATIVE_MARGIN && accountType == null) {
            throw new IllegalArgumentException("accountType is required for derivative margin reservation");
        }
    }
}
