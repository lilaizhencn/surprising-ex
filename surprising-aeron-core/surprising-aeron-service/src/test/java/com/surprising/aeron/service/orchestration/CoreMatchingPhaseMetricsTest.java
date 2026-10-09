package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoreMatchingPhaseMetricsTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(com.surprising.product.api.ProductLine.class)
    void jfrCommandBoundaryRetainsProtocolRoot(com.surprising.product.api.ProductLine product) throws Exception {
        var path = java.nio.file.Files.createTempFile("core-trace-", ".jfr");
        try (var recording = new jdk.jfr.Recording()) {
            recording.enable(CoreMatchingPhaseMetrics.CommandBoundaryLatency.class);
            recording.start();
            var header = com.surprising.aeron.protocol.CoreMessageHeader.query(
                    com.surprising.aeron.protocol.CoreMessageType.STATE_HASH_QUERY, java.util.UUID.randomUUID(), product,
                    com.surprising.aeron.protocol.CommandSource.GATEWAY, 1, 1, 7, 1, 1).withTraceId("request-core-1");
            CoreMatchingPhaseMetrics.recordBoundary("query", header, System.nanoTime());
            recording.stop(); recording.dump(path);
            var events = jdk.jfr.consumer.RecordingFile.readAllEvents(path).stream()
                    .filter(e -> e.getEventType().getName().equals("surprising.CommandBoundaryLatency")).toList();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().getString("traceId")).isEqualTo("request-core-1");
            assertThat(events.getFirst().getString("stage")).isEqualTo("query");
        } finally { java.nio.file.Files.deleteIfExists(path); }
    }

    @Test
    void phaseReportsKeepIndependentCountsAndResetBetweenIntervals() {
        var metrics = new CoreMatchingPhaseMetrics();
        metrics.recordPrepare(1_000);
        metrics.recordPrepare(3_000);
        metrics.recordExchange(9_000);
        metrics.recordApply(4_000);
        assertThat(metrics.reportAndReset()).isEqualTo(
                "prepare=avgMicros=2,maxMicros=3,count=2"
                        + " exchange=avgMicros=9,maxMicros=9,count=1"
                        + " apply=avgMicros=4,maxMicros=4,count=1");
        metrics.recordApply(2_000);
        assertThat(metrics.reportAndReset()).isEqualTo(
                "prepare=avgMicros=0,maxMicros=0,count=0"
                        + " exchange=avgMicros=0,maxMicros=0,count=0"
                        + " apply=avgMicros=2,maxMicros=2,count=1");
    }
}
