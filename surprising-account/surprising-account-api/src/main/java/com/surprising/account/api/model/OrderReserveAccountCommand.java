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
        long orderQuantitySteps,
        boolean reduceOnly,
        long reservedUnits,
        long expectedAccountRevision) {

    public OrderReserveAccountCommand {
        if (orderId <= 0 || symbol == null || symbol.isBlank() || side == null || reservationKind == null
                || accountType == null || asset == null || asset.isBlank()
                || orderQuantitySteps <= 0 || reservedUnits <= 0) {
            throw new IllegalArgumentException("invalid order reservation command");
        }
        marginMode = MarginMode.defaultIfNull(marginMode);
        positionSide = PositionSide.defaultIfNull(positionSide);
        if (expectedAccountRevision < 0L) {
            throw new IllegalArgumentException("expectedAccountRevision must not be negative");
        }
    }

    /** 兼容未启用账户版本栅栏的旧调用方。 */
    public OrderReserveAccountCommand(long orderId,
                                      String symbol,
                                      OrderSide side,
                                      OrderReservationKind reservationKind,
                                      AccountType accountType,
                                      String asset,
                                      MarginMode marginMode,
                                      PositionSide positionSide,
                                      long orderQuantitySteps,
                                      boolean reduceOnly,
                                      long reservedUnits) {
        this(orderId, symbol, side, reservationKind, accountType, asset, marginMode, positionSide,
                orderQuantitySteps, reduceOnly, reservedUnits, 0L);
    }
}
