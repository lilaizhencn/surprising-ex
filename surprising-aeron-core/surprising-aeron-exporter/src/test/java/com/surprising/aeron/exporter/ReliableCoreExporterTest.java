package com.surprising.aeron.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
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
        ReliableCoreExporter exporter = new ReliableCoreExporter(
                ProductLine.SPOT, state::apply, (line, events) -> published.addAll(events), 2);

        var first = exporter.exportOnce();
        var second = exporter.exportOnce();

        assertThat(first.publishedEvents()).isEqualTo(2);
        assertThat(first.status().acknowledgedSequence()).isEqualTo(2);
        assertThat(second.publishedEvents()).isEqualTo(1);
        assertThat(second.status().acknowledgedSequence()).isEqualTo(3);
        assertThat(second.status().pendingCount()).isZero();
        assertThat(published).hasSize(3);
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

        List<CoreMessage> retried = new ArrayList<>();
        ReliableCoreExporter recovered = new ReliableCoreExporter(
                ProductLine.SPOT, state::apply, (line, events) -> retried.addAll(events), 10);
        try {
            assertThat(recovered.exportOnce().publishedEvents()).isEqualTo(2);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        assertThat(retried).hasSize(2);
        assertThat(recovered.status().pendingCount()).isZero();
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
