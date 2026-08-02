package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.LeverageSettingEvent;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import com.surprising.trading.order.model.OrderRecord;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderMarginSnapshotCacheTest {

    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");

    @Test
    void samePositionRevisionWithSameStateIsIdempotent() {
        OrderMarginSnapshotCache cache = new OrderMarginSnapshotCache();
        PositionUpdatedEvent event = position(7L, 10L);

        assertThat(cache.applyPosition(event)).isEqualTo(OrderMarginSnapshotCache.ApplyResult.APPLIED);
        assertThat(cache.applyPosition(event)).isEqualTo(OrderMarginSnapshotCache.ApplyResult.STALE);
    }

    @Test
    void samePositionRevisionWithDifferentStateIsConflict() {
        OrderMarginSnapshotCache cache = new OrderMarginSnapshotCache();
        assertThat(cache.applyPosition(position(7L, 10L)))
                .isEqualTo(OrderMarginSnapshotCache.ApplyResult.APPLIED);

        assertThat(cache.applyPosition(position(7L, 11L)))
                .isEqualTo(OrderMarginSnapshotCache.ApplyResult.CONFLICT);
    }

    @Test
    void sameOrderRevisionWithDifferentStateIsConflict() {
        OrderMarginSnapshotCache cache = new OrderMarginSnapshotCache();
        OrderRecord accepted = order(OrderStatus.ACCEPTED, 3L, 1L);
        OrderRecord canceled = order(OrderStatus.CANCELED, 0L, 1L);

        assertThat(cache.applyOrder(accepted)).isEqualTo(OrderMarginSnapshotCache.ApplyResult.APPLIED);
        assertThat(cache.applyOrder(canceled)).isEqualTo(OrderMarginSnapshotCache.ApplyResult.CONFLICT);
    }

    @Test
    void leverageEventIsOrderedAndIdempotentInJvmSnapshot() {
        OrderMarginSnapshotCache cache = new OrderMarginSnapshotCache();
        LeverageSettingEvent first = leverage(7L, 10_000_000L, NOW);

        assertThat(cache.applyLeverage(first)).isEqualTo(OrderMarginSnapshotCache.ApplyResult.APPLIED);
        assertThat(cache.applyLeverage(first)).isEqualTo(OrderMarginSnapshotCache.ApplyResult.STALE);
        assertThat(cache.lookupConfiguredLeverage(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT",
                MarginMode.CROSS)).contains(10_000_000L);

        assertThat(cache.applyLeverage(leverage(6L, 11_000_000L, NOW.minusSeconds(1))))
                .isEqualTo(OrderMarginSnapshotCache.ApplyResult.STALE);
        assertThat(cache.applyLeverage(leverage(8L, 20_000_000L, NOW.plusSeconds(1))))
                .isEqualTo(OrderMarginSnapshotCache.ApplyResult.APPLIED);
        assertThat(cache.lookupConfiguredLeverage(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT",
                MarginMode.CROSS)).contains(20_000_000L);
    }

    private LeverageSettingEvent leverage(long eventId, long leveragePpm, Instant eventTime) {
        return new LeverageSettingEvent(LeverageSettingEvent.CURRENT_SCHEMA_VERSION, eventId,
                new LeverageSettingRequest(1001L, ProductLine.LINEAR_PERPETUAL, "BTC-USDT",
                        MarginMode.CROSS, leveragePpm, "test"), eventTime);
    }

    private PositionUpdatedEvent position(long revision, long quantity) {
        return new PositionUpdatedEvent(
                PositionUpdatedEvent.CURRENT_SCHEMA_VERSION, revision, 9001L,
                ProductLine.LINEAR_PERPETUAL, revision, 1001L, "BTC-USDT", 1L,
                MarginMode.CROSS, PositionSide.NET, quantity, 60_000L, 60_000L,
                0L, "USDT", 100L, NOW, NOW, NOW, "trace");
    }

    private OrderRecord order(OrderStatus status, long remaining, long revision) {
        return new OrderRecord(8001L, ProductLine.LINEAR_PERPETUAL, 1001L, "client-1", "BTC-USDT", 1L,
                OrderSide.SELL, OrderType.LIMIT, TimeInForce.GTC, 60_000L, 3L,
                3L - remaining, remaining, MarginMode.CROSS, PositionSide.NET,
                0L, 0L, true, false, null, null, 0L, status, null, NOW, NOW, revision);
    }
}
