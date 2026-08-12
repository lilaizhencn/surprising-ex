package com.surprising.risk.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.RiskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RiskLocalProjectionStoreTest {

    @Test
    void batchSurvivesRestartAndProjectionWatermarkIsContinuous() throws Exception {
        Path directory = Files.createTempDirectory("risk-local-projection-");
        RiskLocalProjectionStore.RiskProjectionBatch batch = new RiskLocalProjectionStore.RiskProjectionBatch(
                ProductLine.LINEAR_PERPETUAL,
                List.of(new RiskLocalProjectionStore.RiskProjectionGroup(
                        1001L, "USDT_PERPETUAL", "USDT", 1000L, 0L, 1000L,
                        0L, 0L, RiskStatus.NORMAL, List.of(), List.of(),
                        Instant.parse("2026-08-02T00:00:00Z"), "trace-1")));
        long sequence;
        try (RiskLocalProjectionStore store = new RiskLocalProjectionStore(directory, new ObjectMapper())) {
            sequence = store.append(batch);
            assertThat(store.append(batch)).isEqualTo(sequence);
            assertThat(store.pending(10)).hasSize(1);
            store.assign(sequence, new RiskLocalProjectionStore.ProjectionIds(11L, 21L, 0L));
        }
        try (RiskLocalProjectionStore reopened = new RiskLocalProjectionStore(directory, new ObjectMapper())) {
            RiskLocalProjectionStore.PendingBatch pending = reopened.pending(10).get(0);
            assertThat(pending.sequence()).isEqualTo(sequence);
            assertThat(pending.ids()).contains(new RiskLocalProjectionStore.ProjectionIds(11L, 21L, 0L));
            reopened.markProjected(sequence);
            assertThat(reopened.pending(10)).isEmpty();
            assertThat(reopened.projectedSequence()).isEqualTo(sequence);
        }
    }
}
