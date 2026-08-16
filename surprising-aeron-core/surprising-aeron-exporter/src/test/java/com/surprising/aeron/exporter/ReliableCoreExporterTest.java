package com.surprising.aeron.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.CoreProbeState;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReliableCoreExporterTest {

    @Test
    void acknowledgesOnlyAfterEntireBatchIsPublished() throws Exception {
        CoreProbeState state = stateWithCommands(3);
        List<CoreMessage> published = new ArrayList<>();
        List<CoreMessageType> calls = new ArrayList<>();
        ReliableCoreExporter exporter = new ReliableCoreExporter(
                ProductLine.SPOT, message -> {
                    calls.add(message.header().messageType());
                    return state.apply(message);
                }, (line, events) -> published.addAll(events), 2);

        var first = exporter.exportOnce();
        var second = exporter.exportOnce();

        assertThat(first.publishedEvents()).isEqualTo(2);
        assertThat(first.status().acknowledgedSequence()).isEqualTo(2);
        assertThat(second.publishedEvents()).isEqualTo(1);
        assertThat(second.status().acknowledgedSequence()).isEqualTo(3);
        assertThat(second.status().pendingCount()).isZero();
        assertThat(published).hasSize(3);
        assertThat(calls).containsExactly(
                CoreMessageType.EXPORT_BATCH_QUERY, CoreMessageType.ACK_EXPORT,
                CoreMessageType.EXPORT_BATCH_QUERY, CoreMessageType.ACK_EXPORT);
        assertThat(exporter.health().healthy()).isTrue();
    }

    @Test
    void drainRunsBoundedCyclesUntilBacklogIsEmpty() throws Exception {
        CoreProbeState state = stateWithCommands(5);
        ReliableCoreExporter exporter = new ReliableCoreExporter(
                ProductLine.SPOT, state::apply, (line, events) -> { }, 2);

        assertThat(exporter.drain(3).pendingCount()).isZero();
    }

    @Test
    void sinkFailureLeavesBatchPendingForIdenticalRetry() {
        CoreProbeState state = stateWithCommands(2);
        ReliableCoreExporter failing = new ReliableCoreExporter(
                ProductLine.SPOT, state::apply, (line, events) -> {
                    throw new IllegalStateException("Kafka unavailable");
                }, 10);

        assertThatThrownBy(failing::exportOnce).hasMessage("Kafka unavailable");
        assertThat(failing.status().acknowledgedSequence()).isZero();
        assertThat(failing.status().pendingCount()).isEqualTo(2);
        assertThat(failing.metrics().failureCount()).isEqualTo(1);
        assertThat(failing.metrics().retryCount()).isEqualTo(1);

        List<CoreMessage> retried = new ArrayList<>();
        ReliableCoreExporter recovered = new ReliableCoreExporter(
                ProductLine.SPOT, state::apply, (line, events) -> retried.addAll(events), 10);
        try {
            assertThat(recovered.exportOnce().publishedEvents()).isEqualTo(2);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        assertThat(retried).hasSize(2);
        assertThat(retried).extracting(message -> CoreExportCodec.decodeEvent(message.payload()).exportSequence())
                .containsExactly(1L, 2L);
        assertThat(recovered.status().pendingCount()).isZero();
    }

    @Test
    void unknownAckRetainsOriginalIdAndDoesNotAdvanceSuccess() {
        CoreProbeState state = stateWithCommands(1);
        List<UUID> ackIds = new ArrayList<>();
        ReliableCoreExporter exporter = new ReliableCoreExporter(
                ProductLine.SPOT, message -> {
                    if (message.header().messageType() == CoreMessageType.ACK_EXPORT) {
                        ackIds.add(message.header().commandId());
                        throw new com.surprising.aeron.client.ResultUnknownException(
                                message.header().commandId(), "ack result unknown");
                    }
                    return state.apply(message);
                }, (line, events) -> { }, 10);

        assertThatThrownBy(exporter::exportOnce)
                .isInstanceOf(com.surprising.aeron.client.ResultUnknownException.class);
        assertThatThrownBy(exporter::exportOnce)
                .isInstanceOf(com.surprising.aeron.client.ResultUnknownException.class);

        assertThat(ackIds).hasSize(2).containsOnly(ackIds.getFirst());
        assertThat(exporter.status().acknowledgedSequence()).isZero();
        assertThat(exporter.status().pendingCount()).isEqualTo(1);
        assertThat(exporter.metrics().unknownCount()).isEqualTo(2);
        assertThat(exporter.metrics().retryCount()).isEqualTo(2);
    }

    @Test
    void emptyCycleDoesNotIssueASeparateStatusQuery() throws Exception {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        List<CoreMessageType> calls = new ArrayList<>();
        ReliableCoreExporter exporter = new ReliableCoreExporter(
                ProductLine.SPOT, message -> {
                    calls.add(message.header().messageType());
                    return state.apply(message);
                }, (line, events) -> { }, 2);

        assertThat(exporter.exportOnce().publishedEvents()).isZero();
        assertThat(calls).containsExactly(CoreMessageType.EXPORT_BATCH_QUERY);
    }

    private static CoreProbeState stateWithCommands(int count) {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        for (int sequence = 1; sequence <= count; sequence++) {
            CoreMessage command = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 1, sequence, 0,
                    sequence, sequence), CoreProtocol.probePayload(1));
            assertThat(state.apply(command).status()).isEqualTo(ResponseStatus.APPLIED);
        }
        return state;
    }
}
