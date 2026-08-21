package com.surprising.websocket.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class LayerContinuityAuditorTest {

    private static final String TOPIC = "surprising.linear-perp.core.events.v1";
    private static final long USER_ID = 101L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void reportsDroppedSubscribedClientFrameAfterCatchUp() {
        LayerContinuityAuditor.Report report = report(
                event(WebSocketAuditRecord.Layer.CORE, "event-1", 1, "payload-1", null, null, null),
                event(WebSocketAuditRecord.Layer.CORE, "event-2", 2, "payload-2", null, null, null),
                event(WebSocketAuditRecord.Layer.KAFKA, "event-1", 1, "payload-1", 40L, null, null),
                event(WebSocketAuditRecord.Layer.KAFKA, "event-2", 2, "payload-2", 41L, null, null),
                event(WebSocketAuditRecord.Layer.PROJECTOR, "event-1", 1, "payload-1", null, 2L, 1),
                event(WebSocketAuditRecord.Layer.PROJECTOR, "event-2", 2, "payload-2", null, 2L, 1),
                event(WebSocketAuditRecord.Layer.WEBSOCKET, "event-1", 1, "payload-1", null, null, null),
                WebSocketAuditRecord.catchUp("client-1", USER_ID, TOPIC, 2));

        assertThat(report.permanentWebSocketLosses()).isEqualTo(1);
        assertThat(report.passed()).isFalse();
    }

    @Test
    void separatesLegalByteIdenticalRedeliveryFromMutatedDuplicate() {
        LayerContinuityAuditor.Report legal = report(
                event(WebSocketAuditRecord.Layer.CORE, "event-1", 1, "payload-1", null, null, null),
                event(WebSocketAuditRecord.Layer.KAFKA, "event-1", 1, "payload-1", 40L, null, null),
                event(WebSocketAuditRecord.Layer.KAFKA, "event-1", 1, "payload-1", 40L, null, null),
                event(WebSocketAuditRecord.Layer.PROJECTOR, "event-1", 1, "payload-1", null, 1L, 1),
                event(WebSocketAuditRecord.Layer.WEBSOCKET, "event-1", 1, "payload-1", null, null, null),
                event(WebSocketAuditRecord.Layer.WEBSOCKET, "event-1", 1, "payload-1", null, null, null),
                WebSocketAuditRecord.catchUp("client-1", USER_ID, TOPIC, 1));

        assertThat(legal.kafkaRedeliveries()).isEqualTo(1);
        assertThat(legal.webSocketRedeliveries()).isEqualTo(1);
        assertThat(legal.mutations()).isZero();
        assertThat(legal.passed()).isTrue();

        LayerContinuityAuditor.Report mutated = report(
                event(WebSocketAuditRecord.Layer.CORE, "event-1", 1, "payload-1", null, null, null),
                event(WebSocketAuditRecord.Layer.KAFKA, "event-1", 1, "payload-1", 40L, null, null),
                event(WebSocketAuditRecord.Layer.KAFKA, "event-1", 1, "changed", 40L, null, null),
                event(WebSocketAuditRecord.Layer.PROJECTOR, "event-1", 1, "payload-1", null, 1L, 1),
                event(WebSocketAuditRecord.Layer.WEBSOCKET, "event-1", 1, "payload-1", null, null, null),
                WebSocketAuditRecord.catchUp("client-1", USER_ID, TOPIC, 1));

        assertThat(mutated.mutations()).isEqualTo(1);
        assertThat(mutated.passed()).isFalse();
    }

    @Test
    void reconnectReplayCatchesUpWithoutPermanentLoss() {
        LayerContinuityAuditor.Report report = report(
                event(WebSocketAuditRecord.Layer.CORE, "event-1", 1, "payload-1", null, null, null),
                event(WebSocketAuditRecord.Layer.CORE, "event-2", 2, "payload-2", null, null, null),
                event(WebSocketAuditRecord.Layer.KAFKA, "event-1", 1, "payload-1", 40L, null, null),
                event(WebSocketAuditRecord.Layer.KAFKA, "event-2", 2, "payload-2", 41L, null, null),
                event(WebSocketAuditRecord.Layer.PROJECTOR, "event-1", 1, "payload-1", null, 2L, 1),
                event(WebSocketAuditRecord.Layer.PROJECTOR, "event-2", 2, "payload-2", null, 2L, 1),
                event(WebSocketAuditRecord.Layer.WEBSOCKET, "event-1", 1, "payload-1", null, null, null),
                WebSocketAuditRecord.signal(WebSocketAuditRecord.Type.RECONNECT, "client-1", USER_ID,
                        "connection closed"),
                event(WebSocketAuditRecord.Layer.WEBSOCKET, "event-1", 1, "payload-1", null, null, null),
                event(WebSocketAuditRecord.Layer.WEBSOCKET, "event-2", 2, "payload-2", null, null, null),
                WebSocketAuditRecord.catchUp("client-1", USER_ID, TOPIC, 2));

        assertThat(report.reconnects()).isEqualTo(1);
        assertThat(report.webSocketRedeliveries()).isEqualTo(1);
        assertThat(report.permanentWebSocketLosses()).isZero();
        assertThat(report.passed()).isTrue();
    }

    @Test
    void coreSequenceMustBeContiguousUniqueAndOrdered() {
        LayerContinuityAuditor.Report report = report(
                event(WebSocketAuditRecord.Layer.CORE, "event-1", 1, "payload-1", null, null, null),
                event(WebSocketAuditRecord.Layer.CORE, "event-3", 3, "payload-3", null, null, null),
                event(WebSocketAuditRecord.Layer.CORE, "event-2", 2, "payload-2", null, null, null),
                event(WebSocketAuditRecord.Layer.CORE, "event-2", 2, "payload-2", null, null, null));

        assertThat(report.coreGaps()).isEqualTo(1);
        assertThat(report.coreOutOfOrder()).isEqualTo(1);
        assertThat(report.coreDuplicates()).isEqualTo(1);
        assertThat(report.passed()).isFalse();
    }

    @Test
    void reusedCoreSequenceWithChangedIdentityIsAMutation() {
        LayerContinuityAuditor.Report report = report(
                event(WebSocketAuditRecord.Layer.CORE, "event-1", 1, "payload-1", null, null, null),
                event(WebSocketAuditRecord.Layer.CORE, "event-mutated", 1, "changed", null, null, null));

        assertThat(report.coreDuplicates()).isEqualTo(1);
        assertThat(report.mutations()).isEqualTo(1);
        assertThat(report.passed()).isFalse();
    }

    @Test
    void projectionRequiresExactlyOneFactThroughWatermark() {
        LayerContinuityAuditor.Report report = report(
                event(WebSocketAuditRecord.Layer.CORE, "event-1", 1, "payload-1", null, null, null),
                event(WebSocketAuditRecord.Layer.KAFKA, "event-1", 1, "payload-1", 40L, null, null),
                event(WebSocketAuditRecord.Layer.PROJECTOR, "event-1", 1, "payload-1", null, 1L, 2));

        assertThat(report.projectorDuplicateFacts()).isEqualTo(1);
        assertThat(report.passed()).isFalse();
    }

    @Test
    void recordsAuthenticationFailureAndBoundedQueueRejection() {
        LayerContinuityAuditor.Report report = report(
                WebSocketAuditRecord.signal(WebSocketAuditRecord.Type.AUTH_FAILURE, "client-1", USER_ID,
                        "authentication rejected"),
                WebSocketAuditRecord.signal(WebSocketAuditRecord.Type.QUEUE_REJECTION, "client-2", USER_ID,
                        "capacity=1"));

        assertThat(report.authenticationFailures()).isEqualTo(1);
        assertThat(report.queueRejections()).isEqualTo(1);
        assertThat(report.passed()).isFalse();
    }

    @Test
    void ledgerResumesChecksumChainAndRejectsCorruption() throws Exception {
        Path ledgerPath = temporaryDirectory.resolve("continuity.jsonl");
        for (int sequence = 1; sequence <= 3; sequence++) {
            try (WebSocketAuditLedger resumed = WebSocketAuditLedger.open(ledgerPath, new ObjectMapper())) {
                assertThat(resumed.records()).hasSize(sequence - 1);
                resumed.append(event(WebSocketAuditRecord.Layer.CORE, "event-" + sequence, sequence,
                        "payload-" + sequence, null, null, null));
                resumed.flush();
            }
        }
        try (WebSocketAuditLedger resumed = WebSocketAuditLedger.open(ledgerPath, new ObjectMapper())) {
            assertThat(resumed.records()).extracting(WebSocketAuditRecord::eventId)
                    .containsExactly("event-1", "event-2", "event-3");
        }

        Files.writeString(ledgerPath, "{corrupt}\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> WebSocketAuditLedger.open(ledgerPath, new ObjectMapper()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
    }

    private LayerContinuityAuditor.Report report(WebSocketAuditRecord... records) {
        return new LayerContinuityAuditor().audit(java.util.List.of(records));
    }

    private static WebSocketAuditRecord event(WebSocketAuditRecord.Layer layer,
                                               String eventId,
                                               long sequence,
                                               String payload,
                                               Long kafkaOffset,
                                               Long projectorWatermark,
                                               Integer projectedRows) {
        long now = Instant.parse("2026-08-22T00:00:00Z").toEpochMilli();
        return WebSocketAuditRecord.event(layer, "client-1", eventId, sequence, TOPIC, USER_ID,
                now, now + 1, WebSocketAuditRecord.sha256(payload), kafkaOffset,
                projectorWatermark, projectedRows);
    }
}
