package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.OrderRecord;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ReduceOnlyValidatorTest {

    @Test
    void acceptsSellReduceOnlyForLongPositionFromJvmSnapshot() {
        ReduceOnlyValidator validator = validator(ProductLine.LINEAR_PERPETUAL,
                snapshot(ProductLine.LINEAR_PERPETUAL, 10L, 2L));

        var result = validator.validate(request(OrderSide.SELL, 8L));

        assertThat(result.accepted()).isTrue();
        assertThat(result.instrumentVersion()).isEqualTo(1L);
    }

    @Test
    void rejectsReduceOnlyWithoutPosition() {
        OrderMarginSnapshotCache cache = snapshot(ProductLine.LINEAR_PERPETUAL, 0L, 0L);
        ReduceOnlyValidator validator = validator(ProductLine.LINEAR_PERPETUAL, cache);

        var result = validator.validate(request(OrderSide.SELL, 1L));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("reduce-only requires an open position");
    }

    @Test
    void rejectsSideThatWouldIncreasePosition() {
        ReduceOnlyValidator validator = validator(ProductLine.LINEAR_PERPETUAL,
                snapshot(ProductLine.LINEAR_PERPETUAL, 10L, 0L));

        var result = validator.validate(request(OrderSide.BUY, 1L));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("reduce-only side does not reduce current position");
    }

    @Test
    void rejectsQuantityAbovePositionAfterPendingCloseOrders() {
        ReduceOnlyValidator validator = validator(ProductLine.LINEAR_PERPETUAL,
                snapshot(ProductLine.LINEAR_PERPETUAL, -10L, 4L));

        var result = validator.validate(request(OrderSide.BUY, 7L));

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejectReason()).isEqualTo("reduce-only quantity exceeds available position");
    }

    @Test
    void usesTheSameSnapshotEntryForDelivery() {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        OrderMarginSnapshotCache cache = snapshot(ProductLine.LINEAR_DELIVERY, 10L, 2L);
        ReduceOnlyValidator validator = new ReduceOnlyValidator(properties, cache);

        var result = validator.validate(request(OrderSide.SELL, 8L));

        assertThat(result.accepted()).isTrue();
        assertThat(result.instrumentVersion()).isEqualTo(1L);
    }

    @Test
    void rejectsBeforeSnapshotIsReadyWithoutDatabaseFallback() {
        OrderMarginSnapshotCache cache = new OrderMarginSnapshotCache();
        cache.applyPosition(position(ProductLine.LINEAR_PERPETUAL, 10L));
        ReduceOnlyValidator validator = validator(ProductLine.LINEAR_PERPETUAL, cache);

        var result = validator.validate(request(OrderSide.SELL, 1L));

        assertThat(result.rejectReason()).isEqualTo("reduce-only position snapshot unavailable");
    }

    private OrderMarginSnapshotCache snapshot(ProductLine productLine, long signedQuantitySteps,
                                               long pendingCloseSteps) {
        OrderMarginSnapshotCache cache = new OrderMarginSnapshotCache();
        cache.applyPosition(position(productLine, signedQuantitySteps));
        if (pendingCloseSteps > 0L) {
            OrderSide closeSide = signedQuantitySteps > 0L ? OrderSide.SELL : OrderSide.BUY;
            cache.applyOrder(new OrderRecord(88L, productLine, 1001L, "close-1", "BTC-USDT", 1L,
                    closeSide, OrderType.MARKET, TimeInForce.IOC, 0L, pendingCloseSteps, 0L,
                    pendingCloseSteps, MarginMode.CROSS, PositionSide.NET, 0L, 0L, true, false,
                    null, null, 0L, OrderStatus.ACCEPTED, null, Instant.parse("2026-07-01T00:00:00Z"),
                    Instant.parse("2026-07-01T00:00:00Z"), 1L));
        }
        cache.markReady(productLine);
        return cache;
    }

    private PositionUpdatedEvent position(ProductLine productLine, long signedQuantitySteps) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new PositionUpdatedEvent(1, 5L, 5L, productLine, 1L, 1001L, "BTC-USDT", 1L,
                MarginMode.CROSS, PositionSide.NET, signedQuantitySteps, 60_000L, 0L,
                0L, "", 0L, now, now, now, "trace");
    }

    private PlaceOrderRequest request(OrderSide side, long quantitySteps) {
        return new PlaceOrderRequest(1001L, "c1", "BTC-USDT", side,
                OrderType.MARKET, TimeInForce.IOC, 0L, quantitySteps, true, false);
    }

    private ReduceOnlyValidator validator(ProductLine productLine, OrderMarginSnapshotCache cache) {
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(productLine);
        return new ReduceOnlyValidator(properties, cache);
    }
}
