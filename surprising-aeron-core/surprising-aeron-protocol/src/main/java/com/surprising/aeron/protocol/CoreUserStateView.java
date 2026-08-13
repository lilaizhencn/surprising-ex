package com.surprising.aeron.protocol;

import com.surprising.product.api.ProductLine;
import java.util.List;

public record CoreUserStateView(
        ProductLine productLine,
        long userId,
        long revision,
        List<CoreBalanceView> balances,
        List<CoreReservationView> reservations,
        List<CorePositionView> positions) {

    public CoreUserStateView {
        if (productLine == null || userId <= 0 || revision < 0
                || balances == null || reservations == null || positions == null) {
            throw new IllegalArgumentException("invalid core user state view");
        }
        balances = List.copyOf(balances);
        reservations = List.copyOf(reservations);
        positions = List.copyOf(positions);
    }
}
