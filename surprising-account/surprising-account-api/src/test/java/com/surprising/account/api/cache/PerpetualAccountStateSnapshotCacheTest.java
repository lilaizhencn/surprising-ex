package com.surprising.account.api.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.PositionMode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerpetualAccountStateSnapshotCacheTest {

    @Test
    void rejectsRevisionGapAndDoesNotExposePartialState() {
        PerpetualAccountStateSnapshotCache cache = new PerpetualAccountStateSnapshotCache();
        assertThat(cache.apply(event(1L))).isEqualTo(
                PerpetualAccountStateSnapshotCache.ApplyResult.APPLIED);
        cache.markReady();

        assertThat(cache.apply(event(3L))).isEqualTo(
                PerpetualAccountStateSnapshotCache.ApplyResult.REVISION_GAP);
        assertThat(cache.state(1001L)).isEmpty();
    }

    @Test
    void ignoresOldEventsAndExposesOnlyReadySnapshot() {
        PerpetualAccountStateSnapshotCache cache = new PerpetualAccountStateSnapshotCache();
        cache.apply(event(2L));
        cache.markReady();

        assertThat(cache.apply(event(1L))).isEqualTo(
                PerpetualAccountStateSnapshotCache.ApplyResult.STALE);
        assertThat(cache.state(1001L)).isPresent();
        assertThat(cache.revision(1001L)).isEqualTo(2L);
    }

    private PerpetualAccountStateUpdatedEvent event(long revision) {
        return new PerpetualAccountStateUpdatedEvent(
                1, revision + 100L, revision, ProductLine.LINEAR_PERPETUAL, 1001L, "USDT_PERPETUAL",
                List.of(), List.of(), List.of(), List.of(), List.of(), PositionMode.ONE_WAY,
                Instant.parse("2026-07-01T00:00:00Z"), "trace");
    }
}
