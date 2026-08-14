package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.CorePositionView;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.model.ReduceOnlyPosition;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class OrderPlacementStateService {

    private final OrderAeronGateway aeron;

    public OrderPlacementStateService(OrderAeronGateway aeron) {
        this.aeron = aeron;
    }

    public PositionMode positionMode(ProductLine line, long userId) {
        if (line == ProductLine.SPOT) return PositionMode.ONE_WAY;
        CoreUserStateView state = state(line, userId);
        return PositionMode.valueOf(state.positionMode().name());
    }

    public boolean positionMarginModeConflict(ProductLine line, long userId, String symbol, MarginMode marginMode) {
        if (line == ProductLine.SPOT) return false;
        MarginMode normalized = MarginMode.defaultIfNull(marginMode);
        return state(line, userId).positions().stream()
                .filter(position -> position.symbol().equalsIgnoreCase(symbol))
                .filter(position -> position.signedQuantitySteps() != 0)
                .anyMatch(position -> !position.marginMode().name().equals(normalized.name()));
    }

    public Optional<ReduceOnlyPosition> position(ProductLine line, long userId, String symbol,
                                                 MarginMode mode, PositionSide side) {
        if (line == ProductLine.SPOT) return Optional.empty();
        MarginMode normalizedMode = MarginMode.defaultIfNull(mode);
        PositionSide normalizedSide = PositionSide.defaultIfNull(side);
        return state(line, userId).positions().stream()
                .filter(position -> position.symbol().equalsIgnoreCase(symbol))
                .filter(position -> position.marginMode().name().equals(normalizedMode.name()))
                .filter(position -> position.positionSide().name().equals(normalizedSide.name()))
                .filter(position -> position.signedQuantitySteps() != 0)
                .map(OrderPlacementStateService::position)
                .findFirst();
    }

    private CoreUserStateView state(ProductLine line, long userId) {
        if (line == null) throw new IllegalArgumentException("product line is required");
        CoreUserStateView value = aeron.userState(userId);
        if (value == null) throw new IllegalStateException("Aeron user state not found: " + userId);
        if (value.productLine() != line) throw new IllegalStateException("Aeron user product line mismatch");
        return value;
    }

    private static ReduceOnlyPosition position(CorePositionView value) {
        return new ReduceOnlyPosition(value.signedQuantitySteps(), value.instrumentVersion());
    }
}
