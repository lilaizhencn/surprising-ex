package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.model.CancelAllAfterTimer;
import java.nio.file.Files;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class CancelAllAfterLocalStateStoreTest {

    @TempDir
    java.nio.file.Path directory;

    @Test
    void timerStateSurvivesRestartAndClaimIsIdempotent() throws Exception {
        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        Instant dueAt = now.plusSeconds(1);
        try (CancelAllAfterLocalStateStore store = new CancelAllAfterLocalStateStore(directory,
                new ObjectMapper())) {
            store.upsert(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", 1000L, dueAt,
                    "ACTIVE", now);
            assertThat(store.due(ProductLine.LINEAR_PERPETUAL, dueAt, 100)).hasSize(1);
            assertThat(store.claim(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", dueAt))
                    .get().extracting(CancelAllAfterTimer::status).isEqualTo("TRIGGERING");
            assertThat(store.claim(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", dueAt)).isEmpty();
            store.markTriggered(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", 2, 1, dueAt);
        }

        try (CancelAllAfterLocalStateStore reopened = new CancelAllAfterLocalStateStore(directory,
                new ObjectMapper())) {
            assertThat(reopened.due(ProductLine.LINEAR_PERPETUAL, dueAt.plusSeconds(1), 100)).isEmpty();
            assertThat(reopened.activeTimersForIndex(ProductLine.LINEAR_PERPETUAL, 0, "", 100)).isEmpty();
        }
    }
}
