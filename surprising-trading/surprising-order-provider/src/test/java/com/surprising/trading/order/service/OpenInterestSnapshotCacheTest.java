package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.account.api.model.OpenInterestShardSnapshot;
import com.surprising.account.api.model.OpenInterestShardUpdatedEvent;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenInterestSnapshotCacheTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void replacesAndAggregatesShardSnapshot() {
        OpenInterestSnapshotCache cache = new OpenInterestSnapshotCache();
        cache.replace(ProductLine.LINEAR_PERPETUAL, List.of(
                new OpenInterestShardSnapshot(ProductLine.LINEAR_PERPETUAL, "BTC-USDT", 0,
                        12L, 3L, 1L, NOW),
                new OpenInterestShardSnapshot(ProductLine.LINEAR_PERPETUAL, "BTC-USDT", 1,
                        4L, 9L, 2L, NOW)));

        var value = cache.lookup(ProductLine.LINEAR_PERPETUAL, "btc-usdt").orElseThrow();
        assertThat(value.longQuantitySteps()).isEqualTo(16L);
        assertThat(value.shortQuantitySteps()).isEqualTo(12L);
        assertThat(value.openQuantitySteps()).isEqualTo(16L);
        assertThat(cache.ready(ProductLine.LINEAR_PERPETUAL)).isTrue();
    }

    @Test
    void ignoresOlderShardEventAndAppliesNewAbsoluteValueIdempotently() {
        OpenInterestSnapshotCache cache = new OpenInterestSnapshotCache();
        cache.replace(ProductLine.LINEAR_PERPETUAL, List.of(
                new OpenInterestShardSnapshot(ProductLine.LINEAR_PERPETUAL, "BTC-USDT", 0,
                        10L, 2L, 5L, NOW)));
        OpenInterestShardUpdatedEvent older = event(4L, 20L, 1L);
        OpenInterestShardUpdatedEvent newer = event(6L, 30L, 4L);

        cache.apply(older);
        cache.apply(newer);
        cache.apply(newer);

        var value = cache.lookup(ProductLine.LINEAR_PERPETUAL, "BTC-USDT").orElseThrow();
        assertThat(value.longQuantitySteps()).isEqualTo(30L);
        assertThat(value.shortQuantitySteps()).isEqualTo(4L);
        assertThat(value.revision()).isEqualTo(6L);
    }

    private static OpenInterestShardUpdatedEvent event(long revision, long longQuantity, long shortQuantity) {
        return new OpenInterestShardUpdatedEvent(1, revision, ProductLine.LINEAR_PERPETUAL,
                "BTC-USDT", 0, longQuantity, shortQuantity, revision, NOW);
    }
}
