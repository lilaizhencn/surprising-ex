package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.List;

public record CoreUserStateView(
        ProductLine productLine,
        long userId,
        long revision,
        CorePositionMode positionMode,
        List<CoreBalanceView> balances,
        List<CoreReservationView> reservations,
        List<CorePositionView> positions) {

    public CoreUserStateView {
        if (productLine == null || userId <= 0 || revision < 0 || positionMode == null
                || balances == null || reservations == null || positions == null) {
            throw new IllegalArgumentException("invalid core user state view");
        }
        balances = List.copyOf(balances);
        reservations = List.copyOf(reservations);
        positions = List.copyOf(positions);
    }

    public CoreUserStateView(ProductLine productLine, long userId, long revision,
                             List<CoreBalanceView> balances, List<CoreReservationView> reservations,
                             List<CorePositionView> positions) {
        this(productLine, userId, revision, CorePositionMode.ONE_WAY, balances, reservations, positions);
    }
}
