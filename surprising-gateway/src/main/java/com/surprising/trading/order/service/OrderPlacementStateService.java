package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import org.springframework.stereotype.Service;

@Service
public class OrderPlacementStateService {

    private final OrderAeronGateway aeron;

    public OrderPlacementStateService(OrderAeronGateway aeron) {
        this.aeron = aeron;
    }

    public long requireClosePosition(ProductLine line, long userId, String instrumentId,
                                                   MarginMode mode, PositionSide side) {
        if (line == ProductLine.SPOT) throw new IllegalStateException("open position not found");
        CoreUserStateView state = state(line, userId);
        if (state.positionMode() == com.surprising.aeron.protocol.CorePositionMode.HEDGE
                && !PositionSide.defaultIfNull(side).isHedgeSide()) {
            throw new IllegalArgumentException("positionSide LONG or SHORT is required in HEDGE position mode");
        }
        MarginMode normalizedMode = MarginMode.defaultIfNull(mode);
        PositionSide normalizedSide = PositionSide.defaultIfNull(side);
        for (CorePositionView position : state.positions()) {
            if (position.instrumentId().equalsIgnoreCase(instrumentId)
                    && position.marginMode().name().equals(normalizedMode.name())
                    && position.positionSide().name().equals(normalizedSide.name())
                    && position.signedQuantitySteps() != 0) return position.signedQuantitySteps();
        }
        throw new IllegalStateException("open position not found");
    }

    private CoreUserStateView state(ProductLine line, long userId) {
        if (line == null) throw new IllegalArgumentException("product line is required");
        CoreUserStateView value = aeron.userState(userId);
        if (value == null) throw new IllegalStateException("Aeron user state not found: " + userId);
        if (value.productLine() != line) throw new IllegalStateException("Aeron user product line mismatch");
        return value;
    }

}
