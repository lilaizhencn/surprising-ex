package com.surprising.aeron.service.orchestration;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ContinuousOwnerBenchmarkTest {
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void smallerCommandsAfterBatchReuseLeaveNoTerminalOrdersOrReservations(ProductLine product) {
        try (var workload = new ContinuousOwnerBenchmark()) {
            workload.productLine = product;
            workload.setup();
            var counters = new ContinuousOwnerBenchmark.Counters();
            // Only this regression varies batch size within a trial; JMH parameters remain fixed per fork.
            for (int batchSize : new int[]{20, 1, 20}) {
                workload.batchSize = batchSize;
                workload.placeCancelWithoutTimers(counters);
            }
            assertEquals(512L * 41, counters.terminalBusinessOperations);
            assertEquals(1536, counters.terminalCoreMessages);
            assertEquals(counters.acceptedBusinessOperations, counters.terminalBusinessOperations);
            assertEquals(counters.acceptedCoreMessages, counters.terminalCoreMessages);
        }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void pairedAccountsReleaseDependencyFencesAndRestoreFunds(ProductLine product) {
        try (var workload = new ContinuousOwnerBenchmark()) {
            workload.productLine = product;
            workload.batchSize = 20;
            workload.accountPattern = "PAIRED";
            workload.setup();
            var counters = new ContinuousOwnerBenchmark.Counters();
            workload.placeCancelWithoutTimers(counters);
            assertEquals(10240, counters.terminalBusinessOperations);
            assertEquals(counters.acceptedCoreMessages, counters.terminalCoreMessages);
        }
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "core.settlementLatencyDiagnostics", matches = "true")
    void recordsQueueResidenceAcrossOwnerAndTransportWithoutChangingResponses() throws Exception {
        var path = java.nio.file.Files.createTempFile("command-boundaries-", ".jfr");
        try (var recording = new jdk.jfr.Recording()) {
            recording.enable(CoreMatchingPhaseMetrics.CommandBoundaryLatency.class);
            recording.start();
            try (var workload = new ContinuousOwnerBenchmark()) {
                workload.productLine = ProductLine.LINEAR_PERPETUAL;
                workload.batchSize = 20;
                workload.setup();
                for (int i = 0; i < 4; i++) workload.placeCancelWithoutTimers(new ContinuousOwnerBenchmark.Counters());
                workload.verifyDeferredResponseHandoff();
                workload.verifyPendingSnapshotBoundary();
            }
            recording.stop(); recording.dump(path);
            var events = jdk.jfr.consumer.RecordingFile.readAllEvents(path).stream()
                    .filter(e -> e.getEventType().getName().equals("surprising.CommandBoundaryLatency")).toList();
            org.assertj.core.api.Assertions.assertThat(events).extracting(e -> e.getString("stage"))
                    .contains("transportToOwner", "ingressToAdmission", "admissionExecution", "ownerToEgress");
            for (var event : events) org.assertj.core.api.Assertions.assertThat(event.getLong("elapsedNanos"))
                    .isBetween(0L, java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
        } finally { java.nio.file.Files.deleteIfExists(path); }
    }

    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void immutableBatchResponsesSurviveDeferredTransportAndSnapshot(ProductLine product) {
        try (var workload = new ContinuousOwnerBenchmark()) {
            workload.productLine = product;
            workload.batchSize = 20;
            workload.setup();
            workload.verifyDeferredResponseHandoff();
        }
    }
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void snapshotIncludesOutstandingOrdersAndRoleChangeKeepsOwnerOwnership(ProductLine product) {
        try (var workload = new ContinuousOwnerBenchmark()) {
            workload.productLine = product;
            workload.setup();
            workload.verifyPendingSnapshotBoundary();
        }
    }
    @ParameterizedTest
    @EnumSource(ProductLine.class)
    void continuousOwnerCompletesTailAndRestoresFundsWithoutTimers(ProductLine product) {
        try (var workload = new ContinuousOwnerBenchmark()) {
            workload.productLine = product;
            workload.setup();
            var counters = new ContinuousOwnerBenchmark.Counters();
            workload.placeCancelWithoutTimers(counters);
            assertEquals(512, counters.terminalBusinessOperations);
            assertEquals(counters.acceptedCoreMessages, counters.terminalCoreMessages);
        }
    }
}
