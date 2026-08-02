package com.surprising.account.api.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PositionSnapshotCacheTest {

    @Test
    void onlyNewerRevisionCanReplaceUserSnapshot() {
        PositionSnapshotCache cache = new PositionSnapshotCache(ProductLine.LINEAR_PERPETUAL);

        assertThat(cache.apply(event(10L, 5L))).isEqualTo(PositionSnapshotCache.ApplyResult.APPLIED);
        assertThat(cache.apply(event(9L, 99L))).isEqualTo(PositionSnapshotCache.ApplyResult.STALE);
        assertThat(cache.apply(event(10L, 99L))).isEqualTo(PositionSnapshotCache.ApplyResult.CONFLICT);

        assertThat(cache.position(1001L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET))
                .get().extracting(PositionUpdatedEvent::signedQuantitySteps).isEqualTo(5L);
        assertThat(cache.userRevision(1001L)).hasValue(10L);
    }

    @Test
    void keepsClosedPositionTombstoneButDoesNotExposeItAsOpen() {
        PositionSnapshotCache cache = new PositionSnapshotCache(ProductLine.LINEAR_PERPETUAL);

        cache.apply(event(10L, 5L));
        cache.apply(event(11L, 0L));

        assertThat(cache.position(1001L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET))
                .get().extracting(PositionUpdatedEvent::signedQuantitySteps).isEqualTo(0L);
        assertThat(cache.openPositions(1001L)).isEmpty();
    }

    @Test
    void doesNotRecreateASecondPositionFromAnOlderUserRevision() {
        PositionSnapshotCache cache = new PositionSnapshotCache(ProductLine.LINEAR_PERPETUAL);

        assertThat(cache.apply(event("BTC-USDT", 10L, 5L)))
                .isEqualTo(PositionSnapshotCache.ApplyResult.APPLIED);
        assertThat(cache.apply(event("ETH-USDT", 11L, 2L)))
                .isEqualTo(PositionSnapshotCache.ApplyResult.APPLIED);
        assertThat(cache.apply(event("SOL-USDT", 10L, 3L)))
                .isEqualTo(PositionSnapshotCache.ApplyResult.STALE);
        assertThat(cache.position(1001L, "SOL-USDT", MarginMode.CROSS, PositionSide.NET)).isEmpty();
    }

    @Test
    void rejectsEventsFromAnotherProductLineWithoutChangingState() {
        PositionSnapshotCache cache = new PositionSnapshotCache(ProductLine.LINEAR_PERPETUAL);

        assertThat(cache.apply(event(ProductLine.SPOT, 10L, 5L)))
                .isEqualTo(PositionSnapshotCache.ApplyResult.PRODUCT_LINE_MISMATCH);
        assertThat(cache.snapshot()).isEmpty();
    }

    @Test
    void readinessIsExplicitAndClearRemovesAllState() {
        PositionSnapshotCache cache = new PositionSnapshotCache(ProductLine.LINEAR_PERPETUAL);
        cache.apply(event(10L, 5L));
        cache.markReady();

        assertThat(cache.ready()).isTrue();
        cache.clear();
        assertThat(cache.ready()).isFalse();
        assertThat(cache.snapshot()).isEmpty();
        assertThat(cache.userRevision(1001L)).isEmpty();
    }

    private PositionUpdatedEvent event(long revision, long quantity) {
        return event(ProductLine.LINEAR_PERPETUAL, "BTC-USDT", revision, quantity);
    }

    private PositionUpdatedEvent event(ProductLine productLine, long revision, long quantity) {
        return event(productLine, "BTC-USDT", revision, quantity);
    }

    private PositionUpdatedEvent event(String symbol, long revision, long quantity) {
        return event(ProductLine.LINEAR_PERPETUAL, symbol, revision, quantity);
    }

    private PositionUpdatedEvent event(ProductLine productLine, String symbol, long revision, long quantity) {
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        return new PositionUpdatedEvent(
                PositionUpdatedEvent.CURRENT_SCHEMA_VERSION,
                revision + 100L,
                91L,
                productLine,
                revision,
                1001L,
                symbol,
                7L,
                MarginMode.CROSS,
                PositionSide.NET,
                quantity,
                60_000L,
                quantity * 60_000L,
                0L,
                "USDT",
                20_000L,
                now,
                now,
                now,
                "trace-1");
    }
}
