package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.AckExportCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.CoreExportCodec;
import com.surprising.product.api.ProductLine;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreResultLedgerTest {

    @Test
    void retentionMetadataReusesOwnedResponseBytesWithoutExposingThem() {
        byte[] source = new byte[]{1, 2, 3};
        CoreProbeState.StoredResult created = stored(
                ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 7, source, 0);
        source[0] = 9;

        CoreProbeState.StoredResult retained = created.withRetentionSequence(11);
        byte[] exposed = retained.responseData();
        exposed[1] = 9;

        assertThat(created.responseDataUnsafe()).isSameAs(retained.responseDataUnsafe());
        assertThat(retained.responseData()).containsExactly(1, 2, 3);
        assertThat(retained.retentionSequence()).isEqualTo(11);
    }

    @Test
    void evictedCommandResultIsExplicitlyOutsideRetention() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            UUID firstCommandId = UUID.randomUUID();
            state.apply(probe(firstCommandId, 1, 1));
            for (int sequence = 2; sequence <= CoreProbeState.MAX_IDEMPOTENCY_RESULTS + 1; sequence++) {
                assertThat(state.apply(probe(UUID.randomUUID(), sequence, 1)).status())
                        .isEqualTo(ResponseStatus.APPLIED);
            }

            CoreResponse result = state.apply(commandResultQuery(firstCommandId));

            assertThat(result.status()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(result.resultCode()).isEqualTo(CoreResultCode.fromRejectionCode(
                    "RESULT_UNKNOWN_OUTSIDE_RETENTION"));
        }
    }

    @Test
    void restoredLedgerRejectsResponseBytesOverTheBound() {
        Map<UUID, CoreProbeState.StoredResult> results = new LinkedHashMap<>();
        byte[] response = new byte[512_000];
        Arrays.fill(response, (byte) 7);
        for (int index = 0; index < 70; index++) {
            results.put(UUID.randomUUID(), stored(ResponseStatus.APPLIED, CoreResultCode.NONE,
                    index + 1L, index + 10L, response, index + 1L));
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CoreProbeStateRestoreTestSupport.restore(
                        ProductLine.SPOT, 70, 0, results, Map.of(),
                        com.surprising.aeron.service.state.TradingCoreState.empty(ProductLine.SPOT),
                        new CoreExportState()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("result ledger");
    }

    @Test
    void evictsOldestResultWhenResponseBytesExceedTheBound() throws Exception {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            Method storeResult = CoreProbeState.class.getDeclaredMethod(
                    "storeResult", UUID.class, CoreProbeState.StoredResult.class);
            storeResult.setAccessible(true);
            byte[] response = new byte[12 * 1024 * 1024];
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            UUID third = UUID.randomUUID();

            storeResult.invoke(state, first,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 1, response, 0));
            storeResult.invoke(state, second,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 2, 2, response, 0));
            storeResult.invoke(state, third,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 3, 3, response, 0));

            assertThat(state.commandResults()).doesNotContainKey(first);
            assertThat(state.commandResults()).containsKeys(second, third);
            try (CoreProbeState restored = CoreProbeStateRestoreTestSupport.restore(
                    ProductLine.SPOT, state.appliedCommandCount(),
                    state.probeValue(), state.commandResults(), state.lastSourceSequences(), state.tradingState(),
                    state.exportState())) {
                assertThat(restored.stateHash()).isEqualTo(state.stateHash());
            }
        }
    }

    @Test
    void replacementEvictsOldestOtherResultsAndKeepsTheReplacedKeyBounded() throws Exception {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            Method storeResult = CoreProbeState.class.getDeclaredMethod(
                    "storeResult", UUID.class, CoreProbeState.StoredResult.class);
            storeResult.setAccessible(true);
            UUID oldest = UUID.randomUUID();
            UUID pending = UUID.randomUUID();
            UUID newest = UUID.randomUUID();
            byte[] fourteenMiB = new byte[14 * 1024 * 1024];
            byte[] oneMiB = new byte[1024 * 1024];
            byte[] fourMiB = new byte[4 * 1024 * 1024];

            storeResult.invoke(state, oldest,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 1, fourteenMiB, 0));
            storeResult.invoke(state, pending,
                    stored(ResponseStatus.OK, CoreResultCode.MATCHING_PENDING, 2, 2, oneMiB, 0));
            long pendingRetention = state.commandResults().get(pending).retentionSequence();
            storeResult.invoke(state, newest,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 3, 3, fourteenMiB, 0));

            storeResult.invoke(state, pending,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 4, 4, fourMiB, 0));

            assertThat(state.commandResults()).doesNotContainKey(oldest);
            assertThat(state.commandResults()).containsKeys(pending, newest);
            assertThat(state.commandResults().get(pending).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.commandResults().get(pending).responseData()).hasSize(fourMiB.length);
            assertThat(state.commandResults().get(pending).retentionSequence()).isEqualTo(pendingRetention);
            try (CoreProbeState restored = CoreProbeStateRestoreTestSupport.restore(
                    ProductLine.SPOT, state.appliedCommandCount(),
                    state.probeValue(), state.commandResults(), state.lastSourceSequences(), state.tradingState(),
                    state.exportState())) {
                assertThat(restored.stateHash()).isEqualTo(state.stateHash());
            }
        }
    }

    @Test
    void requiredExportSequenceIsNotAppliedCommandCountAfterExportAck() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            state.apply(probe(UUID.randomUUID(), 1, 1));
            CoreMessage ack = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ACK_EXPORT,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 9, 1, 0,
                    2_000, 81), CoreExportCodec.encodeAck(new AckExportCommand(1)));
            assertThat(state.apply(ack).status()).isEqualTo(ResponseStatus.APPLIED);

            CoreResponse response = state.apply(probe(UUID.randomUUID(), 2, 2));
            assertThat(response.appliedCommandCount()).isEqualTo(3);
            assertThat(response.requiredExportSequence()).isEqualTo(2);
            assertThat(response.requiredExportSequence()).isNotEqualTo(response.appliedCommandCount());
        }
    }

    private static CoreMessage probe(UUID commandId, long sourceSequence, long delta) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId,
                ProductLine.SPOT, CommandSource.GATEWAY, 7, sourceSequence, 1001,
                1_000 + sourceSequence, sourceSequence), CoreProtocol.probePayload(delta));
    }

    private static CoreProbeState.StoredResult stored(
            ResponseStatus status,
            CoreResultCode resultCode,
            long appliedCommandCount,
            long requiredExportSequence,
            byte[] response,
            long retentionSequence) {
        long sourceSequence = Math.max(1, appliedCommandCount);
        CoreMessage command = probe(new UUID(appliedCommandCount, requiredExportSequence), sourceSequence, 1);
        return new CoreProbeState.StoredResult(
                com.surprising.aeron.protocol.CommandFingerprint.of(command), status, resultCode,
                appliedCommandCount, requiredExportSequence, 0, response, retentionSequence);
    }

    private static CoreMessage commandResultQuery(UUID commandId) {
        return new CoreMessage(CoreMessageHeader.query(CoreMessageType.COMMAND_RESULT_QUERY,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 7, 0, 1001, 9_000, 99),
                CoreStateQueryCodec.encodeCommandResultQuery(commandId));
    }
}
