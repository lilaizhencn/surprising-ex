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
    void evictedCommandResultIsExplicitlyOutsideRetention() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
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

    @Test
    void restoredLedgerRejectsResponseBytesOverTheBound() {
        Map<UUID, CoreProbeState.StoredResult> results = new LinkedHashMap<>();
        byte[] response = new byte[512_000];
        Arrays.fill(response, (byte) 7);
        for (int index = 0; index < 70; index++) {
            results.put(UUID.randomUUID(), new CoreProbeState.StoredResult(
                    ResponseStatus.APPLIED, CoreResultCode.NONE, index + 1L, index + 10L, response));
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> CoreProbeState.restore(
                        ProductLine.SPOT, 70, 0, results, Map.of(),
                        com.surprising.aeron.service.state.TradingCoreState.empty(ProductLine.SPOT),
                        new CoreExportState()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("result ledger");
    }

    @Test
    void evictsOldestResultWhenResponseBytesExceedTheBound() throws Exception {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
        Method storeResult = CoreProbeState.class.getDeclaredMethod(
                "storeResult", UUID.class, CoreProbeState.StoredResult.class);
        storeResult.setAccessible(true);
        byte[] response = new byte[12 * 1024 * 1024];
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        storeResult.invoke(state, first,
                new CoreProbeState.StoredResult(ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 1, response));
        storeResult.invoke(state, second,
                new CoreProbeState.StoredResult(ResponseStatus.APPLIED, CoreResultCode.NONE, 2, 2, response));
        storeResult.invoke(state, third,
                new CoreProbeState.StoredResult(ResponseStatus.APPLIED, CoreResultCode.NONE, 3, 3, response));

        assertThat(state.commandResults()).doesNotContainKey(first);
        assertThat(state.commandResults()).containsKeys(second, third);
    }

    @Test
    void replacementEvictsOldestOtherResultsAndKeepsTheReplacedKeyBounded() throws Exception {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
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
                new CoreProbeState.StoredResult(ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 1, fourteenMiB));
        storeResult.invoke(state, pending,
                new CoreProbeState.StoredResult(ResponseStatus.OK, CoreResultCode.MATCHING_PENDING, 2, 2,
                        oneMiB));
        long pendingRetention = state.commandResults().get(pending).retentionSequence();
        storeResult.invoke(state, newest,
                new CoreProbeState.StoredResult(ResponseStatus.APPLIED, CoreResultCode.NONE, 3, 3, fourteenMiB));

        storeResult.invoke(state, pending,
                new CoreProbeState.StoredResult(ResponseStatus.APPLIED, CoreResultCode.NONE, 4, 4, fourMiB));

        assertThat(state.commandResults()).doesNotContainKey(oldest);
        assertThat(state.commandResults()).containsKeys(pending, newest);
        assertThat(state.commandResults().get(pending).status()).isEqualTo(ResponseStatus.APPLIED);
        assertThat(state.commandResults().get(pending).responseData()).hasSize(fourMiB.length);
        assertThat(state.commandResults().get(pending).retentionSequence()).isEqualTo(pendingRetention);
        assertThat(CoreProbeState.restore(ProductLine.SPOT, state.appliedCommandCount(), state.probeValue(),
                state.commandResults(), state.lastSourceSequences(), state.tradingState(), state.exportState()))
                .isNotNull();
    }

    @Test
    void requiredExportSequenceIsNotAppliedCommandCountAfterExportAck() {
        CoreProbeState state = new CoreProbeState(ProductLine.SPOT);
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

    private static CoreMessage probe(UUID commandId, long sourceSequence, long delta) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId,
                ProductLine.SPOT, CommandSource.GATEWAY, 7, sourceSequence, 1001,
                1_000 + sourceSequence, sourceSequence), CoreProtocol.probePayload(delta));
    }

    private static CoreMessage commandResultQuery(UUID commandId) {
        return new CoreMessage(CoreMessageHeader.query(CoreMessageType.COMMAND_RESULT_QUERY,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 7, 0, 1001, 9_000, 99),
                CoreStateQueryCodec.encodeCommandResultQuery(commandId));
    }
}
