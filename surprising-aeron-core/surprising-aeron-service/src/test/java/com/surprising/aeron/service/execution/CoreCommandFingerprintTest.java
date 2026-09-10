package com.surprising.aeron.service.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreCommandFingerprintTest {

    @Test
    void changedPayloadWithSameCommandIdConflictsBeforeAnyMutation() {
        TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT);
        UUID commandId = UUID.randomUUID();

        CoreMessage first = probe(commandId, 1, 7, 1_000, 11);
        CoreMessage changed = probe(commandId, 2, 99, 9_999, 12);

        assertThat(state.apply(first).status()).isEqualTo(ResponseStatus.APPLIED);
        CoreResponse conflict = state.apply(changed);

        assertThat(conflict.resultCode().name()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(state.probeValue()).isEqualTo(7);
        assertThat(state.appliedCommandCount()).isOne();
        assertThat(state.exportState().nextSequence()).isEqualTo(1);
        assertThat(state.lastSourceSequences())
                .containsEntry(new TradingCoreRuntime.SourceKey(CommandSource.GATEWAY, 7), 1L);
    }

    @Test
    void sameFingerprintReplaysOriginalAfterSourceEpochAndSnapshotRestore() {
        TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT);
        UUID commandId = UUID.randomUUID();
        CoreResponse original = state.apply(probe(commandId, 1, 7, 1_000, 11));
        byte[] snapshot = state.snapshot();
        TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, snapshot);
        long stateHash = restored.stateHash();
        long appliedCommandCount = restored.appliedCommandCount();
        long exportSequence = restored.exportState().nextSequence();
        Map<TradingCoreRuntime.SourceKey, Long> sourceSequences = restored.lastSourceSequences();

        CoreResponse replay = restored.apply(new CoreMessage(
                CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId, ProductLine.SPOT,
                        CommandSource.GATEWAY, 999, 4_000, 1001, 8_000, 44),
                CoreProtocol.probePayload(7)));

        assertThat(replay.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(replay.commandStatus()).isEqualTo(original.commandStatus());
        assertThat(replay.resultCode()).isEqualTo(original.resultCode());
        assertThat(replay.appliedCommandCount()).isEqualTo(original.appliedCommandCount());
        assertThat(replay.requiredExportSequence()).isEqualTo(original.requiredExportSequence());
        assertThat(replay.stateHash()).isEqualTo(original.stateHash());
        assertThat(replay.data()).containsExactly(original.data());
        assertThat(restored.stateHash()).isEqualTo(stateHash);
        assertThat(restored.appliedCommandCount()).isEqualTo(appliedCommandCount);
        assertThat(restored.exportState().nextSequence()).isEqualTo(exportSequence);
        assertThat(restored.lastSourceSequences()).isEqualTo(sourceSequences);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ProductLine.class)
    void preparedFingerprintIsRetainedAndConflictsSurviveSnapshot(ProductLine product) {
        UUID id = UUID.randomUUID();
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                id, product, CommandSource.GATEWAY, 7, 1, 1001, 1000, 11), CoreProtocol.probePayload(7));
        var fingerprint = com.surprising.aeron.protocol.CommandFingerprint.of(message);
        try (var state = new TradingCoreRuntime(product)) {
            var response = state.applyDecodedCommand(message, 1000, 1, null, false, fingerprint);
            assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.resultLedger.get(id).fingerprint()).isSameAs(fingerprint);
            try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(restored.apply(message).status()).isEqualTo(ResponseStatus.DUPLICATE);
                var changed = new CoreMessage(message.header(), CoreProtocol.probePayload(99));
                assertThat(restored.applyDecodedCommand(changed, 1000, 2, null, false,
                        com.surprising.aeron.protocol.CommandFingerprint.of(changed)).resultCode().name())
                        .isEqualTo("IDEMPOTENCY_CONFLICT");
                assertThat(restored.probeValue()).isEqualTo(7);
            }
            var next = new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT,
                    UUID.randomUUID(), product, CommandSource.GATEWAY, 7, 2, 1001, 1001, 12),
                    CoreProtocol.probePayload(3));
            assertThat(state.apply(next).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.probeValue()).isEqualTo(10);
        }
    }

    private static CoreMessage probe(UUID commandId, long sourceSequence, long delta,
                                     long submittedAt, long correlationId) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId,
                ProductLine.SPOT, CommandSource.GATEWAY, 7, sourceSequence, 1001,
                submittedAt, correlationId), CoreProtocol.probePayload(delta));
    }
}
