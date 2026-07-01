package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.model.ReduceOnlyPosition;
import com.surprising.trading.order.model.ReduceOnlyPositionLookup;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReduceOnlyValidatorTest {

    @Test
    void acceptsSellReduceOnlyForLongPosition() {
        ReduceOnlyValidator validator = new ReduceOnlyValidator(lookup(10L, 2L));

        var result = validator.validate(request(OrderSide.SELL, 8L));

        assertThat(result.accepted()).isTrue();
        assertThat(result.instrumentVersion()).isEqualTo(1L);
    }

    @Test
    void rejectsReduceOnlyWithoutPosition() {
        ReduceOnlyValidator validator = new ReduceOnlyValidator(lookup(0L, 0L));

        var result = validator.validate(request(OrderSide.SELL, 1L));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("reduce-only requires an open position");
    }

    @Test
    void rejectsSideThatWouldIncreasePosition() {
        ReduceOnlyValidator validator = new ReduceOnlyValidator(lookup(10L, 0L));

        var result = validator.validate(request(OrderSide.BUY, 1L));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("reduce-only side does not reduce current position");
    }

    @Test
    void rejectsQuantityAbovePositionAfterPendingCloseOrders() {
        ReduceOnlyValidator validator = new ReduceOnlyValidator(lookup(-10L, 4L));

        var result = validator.validate(request(OrderSide.BUY, 7L));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("reduce-only quantity exceeds available position");
    }

    private PlaceOrderRequest request(OrderSide side, long quantitySteps) {
        return new PlaceOrderRequest(1001L, "c1", "BTC-USDT", side,
                OrderType.MARKET, TimeInForce.IOC, 0L, quantitySteps, true, false);
    }

    private ReduceOnlyPositionLookup lookup(long signedQuantitySteps, long pendingCloseSteps) {
        return new ReduceOnlyPositionLookup() {
            @Override
            public Optional<ReduceOnlyPosition> lockedPosition(long userId, String symbol) {
                return Optional.of(new ReduceOnlyPosition(signedQuantitySteps,
                        signedQuantitySteps == 0 ? 0L : 1L));
            }

            @Override
            public long lockedOpenReduceOnlySteps(long userId, String symbol, long instrumentVersion,
                                                  OrderSide closeSide) {
                return pendingCloseSteps;
            }
        };
    }
}
