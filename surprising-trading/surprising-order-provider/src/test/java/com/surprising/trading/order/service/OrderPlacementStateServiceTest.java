package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.account.api.cache.PerpetualAccountStateSnapshotCache;
import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderPlacementStateServiceTest {

    @Test
    void perpetualPositionModeComesFromReadyAccountSnapshot() {
        PerpetualAccountStateSnapshotCache cache = new PerpetualAccountStateSnapshotCache(ProductLine.LINEAR_PERPETUAL);
        PerpetualAccountStateUpdatedEvent event = new PerpetualAccountStateUpdatedEvent(
                PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION, 11L, 7L,
                ProductLine.LINEAR_PERPETUAL, 1001L, "USDT_PERPETUAL",
                List.of(), List.of(), List.of(), List.of(), List.of(), PositionMode.HEDGE,
                Instant.parse("2026-07-01T00:00:00Z"), "trace");
        cache.apply(event);
        cache.markReady();

        OrderPlacementStateService service = new OrderPlacementStateService(cache, null);

        assertThat(service.positionMode(ProductLine.LINEAR_PERPETUAL, 1001L)).isEqualTo(PositionMode.HEDGE);
    }

    @Test
    void mismatchedProductLineFailsClosedInsteadOfQueryingDatabase() {
        OrderPlacementStateService service = new OrderPlacementStateService(
                new PerpetualAccountStateSnapshotCache(ProductLine.LINEAR_PERPETUAL), null);

        assertThatThrownBy(() -> service.positionMode(ProductLine.LINEAR_DELIVERY, 1001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("产品线不一致");
    }

    @Test
    void missingUserSnapshotFailsClosed() {
        PerpetualAccountStateSnapshotCache cache = new PerpetualAccountStateSnapshotCache(ProductLine.LINEAR_PERPETUAL);
        cache.markReady();
        OrderPlacementStateService service = new OrderPlacementStateService(cache, null);

        assertThatThrownBy(() -> service.positionMode(ProductLine.LINEAR_PERPETUAL, 1001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("快照不存在");
    }
}
