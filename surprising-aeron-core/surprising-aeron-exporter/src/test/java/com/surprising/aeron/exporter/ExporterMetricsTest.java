package com.surprising.aeron.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreExportStatus;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExporterMetricsTest {

    @Test
    void recordsUnknownAndProjectionLag() {
        AtomicLong clock = new AtomicLong(1_000L);
        ExporterMetrics metrics = new ExporterMetrics(ProductLine.SPOT, clock::get);
        CoreMessage event = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 1, 1, 17, 900, 1)
                .exportEvent(1), new byte[0]);

        metrics.observeBatch(new CoreExportStatus(0, 2, 1, 64, 1_000, 1_000_000), java.util.List.of(event));
        clock.addAndGet(150L);
        assertThat(metrics.snapshot().oldestEventAgeMillis()).isEqualTo(250L);

        metrics.recordQuery();
        clock.addAndGet(1_000L);
        metrics.recordQuery();
        metrics.recordPublished(1, 1);
        metrics.recordAcknowledged(new CoreExportStatus(1, 2, 0, 0, 1_000, 1_000_000));
        metrics.recordProjectionWatermark(0);
        metrics.recordDuplicate(1);
        metrics.recordRetry();
        metrics.recordFailure();
        metrics.recordUnknown();
        metrics.recordReconnect(true);

        ExporterMetrics.Snapshot snapshot = metrics.snapshot();
        assertThat(snapshot.productLine()).isEqualTo(ProductLine.SPOT);
        assertThat(snapshot.backlogCount()).isZero();
        assertThat(snapshot.backlogBytes()).isZero();
        assertThat(snapshot.oldestEventAgeMillis()).isZero();
        assertThat(snapshot.lastAcknowledgedSequence()).isEqualTo(1);
        assertThat(snapshot.kafkaToPgWatermarkLag()).isEqualTo(1);
        assertThat(snapshot.queryRatePerSecond()).isEqualTo(2.0);
        assertThat(snapshot.publishedEvents()).isEqualTo(1);
        assertThat(snapshot.duplicateEvents()).isEqualTo(1);
        assertThat(snapshot.retryCount()).isEqualTo(1);
        assertThat(snapshot.failureCount()).isEqualTo(1);
        assertThat(snapshot.unknownCount()).isEqualTo(1);
        assertThat(snapshot.reconnectCount()).isEqualTo(1);
        assertThat(snapshot.reconnecting()).isTrue();
    }
}
