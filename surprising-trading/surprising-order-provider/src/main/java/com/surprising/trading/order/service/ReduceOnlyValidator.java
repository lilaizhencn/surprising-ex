package com.surprising.trading.order.service;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.order.model.ReduceOnlyPositionLookup;
import com.surprising.trading.order.model.ValidationResult;
import org.springframework.stereotype.Component;

@Component
public class ReduceOnlyValidator {

    private final ReduceOnlyPositionLookup positionLookup;

    public ReduceOnlyValidator(ReduceOnlyPositionLookup positionLookup) {
        this.positionLookup = positionLookup;
    }

    public ValidationResult validate(PlaceOrderRequest request) {
        if (!request.reduceOnly()) {
            return ValidationResult.ok();
        }
        var position = positionLookup.lockedPosition(request.userId(), request.symbol(), request.marginMode()).orElse(null);
        long signedQuantity = position == null ? 0L : position.signedQuantitySteps();
        if (signedQuantity == 0) {
            return ValidationResult.reject("reduce-only requires an open position");
        }
        if (position.instrumentVersion() <= 0) {
            return ValidationResult.reject("reduce-only position instrument version is missing");
        }
        OrderSide closeSide = signedQuantity > 0 ? OrderSide.SELL : OrderSide.BUY;
        if (request.side() != closeSide) {
            return ValidationResult.reject("reduce-only side does not reduce current position");
        }
        long pendingCloseSteps = positionLookup.lockedOpenReduceOnlySteps(request.userId(), request.symbol(),
                request.marginMode(), position.instrumentVersion(), closeSide);
        long availableCloseSteps = Math.subtractExact(Math.absExact(signedQuantity), pendingCloseSteps);
        if (availableCloseSteps <= 0) {
            return ValidationResult.reject("no reducible quantity is available");
        }
        if (request.quantitySteps() > availableCloseSteps) {
            return ValidationResult.reject("reduce-only quantity exceeds available position");
        }
        return ValidationResult.ok(position.instrumentVersion());
    }
}
