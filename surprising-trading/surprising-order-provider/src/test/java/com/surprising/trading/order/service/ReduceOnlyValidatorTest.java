package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.model.OrderRecord;
import java.time.Instant;
import com.surprising.trading.order.config.TradingOrderProperties;
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

    @Test
    void readsPositionAndPendingCloseOrdersInsideConfiguredProductLine() {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        ReduceOnlyValidator validator = new ReduceOnlyValidator(lookup(ProductLine.LINEAR_DELIVERY, 10L, 2L),
                properties);

        var result = validator.validate(request(OrderSide.SELL, 8L));

        assertThat(result.accepted()).isTrue();
        assertThat(result.instrumentVersion()).isEqualTo(1L);
    }

    @Test
    void perpetualUsesJvmSnapshotAndFailsClosedBeforeItCanQueryDatabase() {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        OrderMarginSnapshotCache cache = new OrderMarginSnapshotCache();
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        cache.applyPosition(new PositionUpdatedEvent(5L, 5L, 1001L, "BTC-USDT", 1L,
                MarginMode.CROSS, PositionSide.NET, 10L, 60_000L, 0L, now, "trace"));
        cache.applyOrder(new OrderRecord(88L, ProductLine.LINEAR_PERPETUAL, 1001L, "close-1", "BTC-USDT", 1L,
                OrderSide.SELL, OrderType.MARKET, TimeInForce.IOC, 0L, 2L, 0L, 2L, MarginMode.CROSS,
                PositionSide.NET, 0L, 0L, true, false, null, null, 0L, OrderStatus.ACCEPTED, null, now, now, 1L));

        ReduceOnlyValidator notReady = new ReduceOnlyValidator(null, properties, cache);
        assertThat(notReady.validate(request(OrderSide.SELL, 1L)).rejectReason())
                .isEqualTo("reduce-only position snapshot unavailable");

        cache.markReady(ProductLine.LINEAR_PERPETUAL);
        ReduceOnlyValidator validator = new ReduceOnlyValidator(lookup(10L, 2L), properties, cache);
        assertThat(validator.validate(request(OrderSide.SELL, 8L)).accepted()).isTrue();
        assertThat(validator.validate(request(OrderSide.SELL, 9L)).rejectReason())
                .isEqualTo("reduce-only quantity exceeds available position");
    }

    private PlaceOrderRequest request(OrderSide side, long quantitySteps) {
        return new PlaceOrderRequest(1001L, "c1", "BTC-USDT", side,
                OrderType.MARKET, TimeInForce.IOC, 0L, quantitySteps, true, false);
    }

    private ReduceOnlyPositionLookup lookup(long signedQuantitySteps, long pendingCloseSteps) {
        return new ReduceOnlyPositionLookup() {
            @Override
            public Optional<ReduceOnlyPosition> lockedPosition(long userId, String symbol, MarginMode marginMode) {
                assertThat(marginMode).isEqualTo(MarginMode.CROSS);
                return Optional.of(new ReduceOnlyPosition(signedQuantitySteps,
                        signedQuantitySteps == 0 ? 0L : 1L));
            }

            @Override
            public long lockedOpenReduceOnlySteps(long userId, String symbol, MarginMode marginMode, long instrumentVersion,
                                                  OrderSide closeSide) {
                assertThat(marginMode).isEqualTo(MarginMode.CROSS);
                return pendingCloseSteps;
            }
        };
    }

    private ReduceOnlyPositionLookup lookup(ProductLine expectedProductLine,
                                            long signedQuantitySteps,
                                            long pendingCloseSteps) {
        return new ReduceOnlyPositionLookup() {
            @Override
            public Optional<ReduceOnlyPosition> lockedPosition(ProductLine productLine,
                                                               long userId,
                                                               String symbol,
                                                               MarginMode marginMode,
                                                               com.surprising.trading.api.model.PositionSide positionSide) {
                assertThat(productLine).isEqualTo(expectedProductLine);
                assertThat(marginMode).isEqualTo(MarginMode.CROSS);
                return Optional.of(new ReduceOnlyPosition(signedQuantitySteps, 1L));
            }

            @Override
            public long lockedOpenReduceOnlySteps(ProductLine productLine,
                                                  long userId,
                                                  String symbol,
                                                  MarginMode marginMode,
                                                  long instrumentVersion,
                                                  com.surprising.trading.api.model.PositionSide positionSide,
                                                  OrderSide closeSide) {
                assertThat(productLine).isEqualTo(expectedProductLine);
                assertThat(marginMode).isEqualTo(MarginMode.CROSS);
                return pendingCloseSteps;
            }
        };
    }
}
