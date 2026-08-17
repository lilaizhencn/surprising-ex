package com.surprising.aeron.exporter;

import com.surprising.aeron.protocol.CoreExportStatus;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class ExporterMetrics {

    private final ProductLine productLine;
    private final LongSupplier clockMillis;
    private long backlogCount;
    private long backlogBytes;
    private long oldestEventSubmittedAt = -1;
    private long lastAcknowledgedSequence;
    private long exportToKafkaLag;
    private long lastPublishedSequence;
    private long projectionWatermark;
    private long kafkaToPgWatermarkLag;
    private long publishedEvents;
    private long duplicateEvents;
    private long retryCount;
    private long failureCount;
    private long unknownCount;
    private long reconnectCount;
    private boolean reconnecting;
    private long queryCount;
    private long firstQueryAt = -1;
    private long lastQueryAt = -1;

    public ExporterMetrics(ProductLine productLine) {
        this(productLine, System::currentTimeMillis);
    }

    public ExporterMetrics(ProductLine productLine, LongSupplier clockMillis) {
        this.productLine = Objects.requireNonNull(productLine, "productLine");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    public synchronized void observeBatch(CoreExportStatus status, List<CoreMessage> events) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(events, "events");
        updateStatus(status);
        if (status.pendingCount() == 0) {
            oldestEventSubmittedAt = -1;
        } else if (!events.isEmpty()) {
            oldestEventSubmittedAt = events.stream()
                    .mapToLong(message -> message.header().submittedAtEpochMillis())
                    .min().orElse(-1);
        }
    }

    public synchronized void recordAcknowledged(CoreExportStatus status) {
        Objects.requireNonNull(status, "status");
        updateStatus(status);
        if (status.pendingCount() == 0) {
            oldestEventSubmittedAt = -1;
        }
    }

    public synchronized void recordQuery() {
        long now = clockMillis.getAsLong();
        if (firstQueryAt < 0) {
            firstQueryAt = now;
        }
        lastQueryAt = now;
        queryCount = Math.incrementExact(queryCount);
    }

    public synchronized void recordPublished(int count, long throughSequence) {
        if (count < 0 || throughSequence < 0) {
            throw new IllegalArgumentException("invalid published export metrics");
        }
        publishedEvents = Math.addExact(publishedEvents, count);
        lastPublishedSequence = Math.max(lastPublishedSequence, throughSequence);
        kafkaToPgWatermarkLag = Math.max(0, lastPublishedSequence - projectionWatermark);
    }

    public synchronized void recordDuplicate(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("duplicate count must not be negative");
        }
        duplicateEvents = Math.addExact(duplicateEvents, count);
    }

    public synchronized void recordRetry() {
        retryCount = Math.incrementExact(retryCount);
    }

    public synchronized void recordFailure() {
        failureCount = Math.incrementExact(failureCount);
    }

    public synchronized void recordUnknown() {
        unknownCount = Math.incrementExact(unknownCount);
    }

    public synchronized void recordReconnect(boolean reconnecting) {
        if (reconnecting && !this.reconnecting) {
            reconnectCount = Math.incrementExact(reconnectCount);
        }
        this.reconnecting = reconnecting;
    }

    public synchronized void recordProjectionWatermark(long watermark) {
        if (watermark < 0) {
            throw new IllegalArgumentException("projection watermark must not be negative");
        }
        projectionWatermark = Math.max(projectionWatermark, watermark);
        kafkaToPgWatermarkLag = Math.max(0, lastPublishedSequence - projectionWatermark);
    }

    public synchronized Snapshot snapshot() {
        long now = clockMillis.getAsLong();
        long oldestAge = oldestEventSubmittedAt < 0
                ? 0 : Math.max(0, now - oldestEventSubmittedAt);
        double queryRate = queryCount == 0 || lastQueryAt <= firstQueryAt
                ? 0.0 : queryCount * 1_000.0 / (lastQueryAt - firstQueryAt);
        return new Snapshot(productLine, backlogCount, backlogBytes, oldestAge, lastAcknowledgedSequence,
                exportToKafkaLag, kafkaToPgWatermarkLag, publishedEvents, duplicateEvents, retryCount,
                failureCount, unknownCount, reconnectCount, reconnecting, queryCount, queryRate);
    }

    private void updateStatus(CoreExportStatus status) {
        backlogCount = status.pendingCount();
        backlogBytes = status.pendingBytes();
        lastAcknowledgedSequence = status.acknowledgedSequence();
        exportToKafkaLag = Math.max(0, status.nextSequence() - 1 - status.acknowledgedSequence());
    }

    public record Snapshot(
            ProductLine productLine,
            long backlogCount,
            long backlogBytes,
            long oldestEventAgeMillis,
            long lastAcknowledgedSequence,
            long exportToKafkaLag,
            long kafkaToPgWatermarkLag,
            long publishedEvents,
            long duplicateEvents,
            long retryCount,
            long failureCount,
            long unknownCount,
            long reconnectCount,
            boolean reconnecting,
            long queryCount,
            double queryRatePerSecond) {
    }
}
