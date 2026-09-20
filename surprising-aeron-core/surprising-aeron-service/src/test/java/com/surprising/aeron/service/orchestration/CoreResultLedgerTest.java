package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.product.api.ProductLine;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreResultLedgerTest {

    @Test
    void sustainedTurnoverPreservesLookupRetentionAndRestoreOrder() {
        var ledger = new CommandResultLedger(new LinkedHashMap<>());
        var expected = new LinkedHashMap<UUID, long[]>();
        var random = new java.util.Random(915);
        var ids = new UUID[1024];
        for (int i = 0; i < ids.length; i++) ids[i] = new UUID(random.nextLong(), random.nextLong());
        long nextRetention = 1;
        for (int sequence = 1; sequence <= 20_000; sequence++) {
            UUID id = ids[random.nextInt(ids.length)];
            long retention = expected.containsKey(id) ? expected.get(id)[1] : nextRetention++;
            expected.put(id, new long[]{sequence, retention});
            if (expected.size() > TradingCoreRuntime.MAX_IDEMPOTENCY_RESULTS)
                expected.remove(expected.keySet().iterator().next());
            ledger.storeResult(id, stored(ResponseStatus.APPLIED, CoreResultCode.NONE,
                    sequence, 0, new byte[]{(byte) sequence}, 0));
            assertThat(ledger.get(id).appliedCommandCount()).isEqualTo(sequence);
            if (sequence % 97 != 0) continue;
            assertThat(ledger.entries().keySet()).containsExactlyElementsOf(expected.keySet());
            for (var entry : expected.entrySet()) {
                var result = ledger.get(entry.getKey());
                assertThat(result.appliedCommandCount()).isEqualTo(entry.getValue()[0]);
                assertThat(result.retentionSequence()).isEqualTo(entry.getValue()[1]);
            }
            assertThat(ledger.get(new UUID(0, -sequence))).isNull();
            if (sequence % 970 == 0)
                ledger = new CommandResultLedger(new LinkedHashMap<>(ledger.entries()));
        }
    }

    @Test
    void ownedInsertionPreservesSequenceAndPriorResultWhenReplacing() {
        var ledger = new CommandResultLedger(new LinkedHashMap<>());
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var fingerprint = com.surprising.aeron.protocol.CommandFingerprint.of(probe(first, 1, 1));
        byte[] originalBytes = {1, 2};
        ledger.storeOwnedResult(first, fingerprint, ResponseStatus.APPLIED, CoreResultCode.NONE,
                1, originalBytes);
        var original = ledger.get(first);
        ledger.storeOwnedResult(second, fingerprint, ResponseStatus.APPLIED, CoreResultCode.NONE,
                2, new byte[]{3});
        byte[] replacementBytes = {4};
        ledger.storeOwnedResult(first, fingerprint, ResponseStatus.APPLIED, CoreResultCode.NONE,
                3, replacementBytes);
        assertThat(original.responseDataUnsafe()).isSameAs(originalBytes);
        assertThat(original.responseData()).containsExactly(1, 2);
        assertThat(ledger.get(first).responseDataUnsafe()).isSameAs(replacementBytes);
        assertThat(ledger.get(first).retentionSequence()).isEqualTo(original.retentionSequence());
        assertThat(ledger.entries().keySet()).containsExactly(first, second);
        CommandResultLedger.validateResultLedger(ledger.entries());
    }

    @Test
    void retentionMetadataReusesOwnedResponseBytesWithoutExposingThem() {
        byte[] source = new byte[]{1, 2, 3};
        CommandResultLedger.StoredResult created = stored(
                ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 7, source, 0);
        source[0] = 9;

        CommandResultLedger.StoredResult retained = created.withRetentionSequence(11);
        byte[] exposed = retained.responseData();
        exposed[1] = 9;

        assertThat(created.responseDataUnsafe()).isSameAs(retained.responseDataUnsafe());
        assertThat(retained.responseData()).containsExactly(1, 2, 3);
        assertThat(retained.retentionSequence()).isEqualTo(11);
    }

    @Test
    void retainedResultCachesItsCommandBoundDigestWithoutChangingLedgerSemantics() {
        UUID commandId = UUID.randomUUID();
        CommandResultLedger.StoredResult retained = stored(
                ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 7, new byte[]{1, 2, 3}, 11);

        long first = retained.entryDigest(commandId);
        long second = retained.entryDigest(commandId);
        long differentCommand = retained.entryDigest(UUID.randomUUID());

        assertThat(second).isEqualTo(first);
        assertThat(differentCommand).isNotEqualTo(first);
        assertThat(retained.entryDigest(commandId)).isEqualTo(first);
    }

    @Test
    void evictedCommandResultIsExplicitlyOutsideRetention() {
        try (TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            UUID firstCommandId = UUID.randomUUID();
            state.apply(probe(firstCommandId, 1, 1));
            for (int sequence = 2; sequence <= TradingCoreRuntime.MAX_IDEMPOTENCY_RESULTS + 1; sequence++) {
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
        Map<UUID, CommandResultLedger.StoredResult> results = new LinkedHashMap<>();
        byte[] response = new byte[512_000];
        Arrays.fill(response, (byte) 7);
        for (int index = 0; index < 70; index++) {
            results.put(UUID.randomUUID(), stored(ResponseStatus.APPLIED, CoreResultCode.NONE,
                    index + 1L, index + 10L, response, index + 1L));
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> TradingCoreRuntimeRestoreTestSupport.restore(
                        ProductLine.SPOT, 70, 0, results, Map.of(),
                        com.surprising.aeron.service.state.TradingCoreState.empty(ProductLine.SPOT)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("result ledger");
    }

    @Test
    void evictsOldestResultWhenResponseBytesExceedTheBound() throws Exception {
        try (TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            Method storeResult = CommandResultLedger.class.getDeclaredMethod(
                    "storeResult", UUID.class, CommandResultLedger.StoredResult.class);
            storeResult.setAccessible(true);
            byte[] response = new byte[12 * 1024 * 1024];
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            UUID third = UUID.randomUUID();

            storeResult.invoke(state.resultLedger, first,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 1, response, 0));
            storeResult.invoke(state.resultLedger, second,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 2, 2, response, 0));
            storeResult.invoke(state.resultLedger, third,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 3, 3, response, 0));

            assertThat(state.commandResults()).doesNotContainKey(first);
            assertThat(state.commandResults()).containsKeys(second, third);
            try (TradingCoreRuntime restored = TradingCoreRuntimeRestoreTestSupport.restore(
                    ProductLine.SPOT, state.appliedCommandCount(),
                    state.probeValue(), state.commandResults(), state.lastSourceSequences(), state.tradingState())) {
                assertThat(restored.stateHash()).isEqualTo(state.stateHash());
            }
        }
    }

    @Test
    void replacementEvictsOldestOtherResultsAndKeepsTheReplacedKeyBounded() throws Exception {
        try (TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            Method storeResult = CommandResultLedger.class.getDeclaredMethod(
                    "storeResult", UUID.class, CommandResultLedger.StoredResult.class);
            storeResult.setAccessible(true);
            UUID oldest = UUID.randomUUID();
            UUID pending = UUID.randomUUID();
            UUID newest = UUID.randomUUID();
            byte[] fourteenMiB = new byte[14 * 1024 * 1024];
            byte[] oneMiB = new byte[1024 * 1024];
            byte[] fourMiB = new byte[4 * 1024 * 1024];

            storeResult.invoke(state.resultLedger, oldest,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 1, 1, fourteenMiB, 0));
            storeResult.invoke(state.resultLedger, pending,
                    stored(ResponseStatus.OK, CoreResultCode.MATCHING_PENDING, 2, 2, oneMiB, 0));
            long pendingRetention = state.commandResults().get(pending).retentionSequence();
            storeResult.invoke(state.resultLedger, newest,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 3, 3, fourteenMiB, 0));

            storeResult.invoke(state.resultLedger, pending,
                    stored(ResponseStatus.APPLIED, CoreResultCode.NONE, 4, 4, fourMiB, 0));

            assertThat(state.commandResults()).doesNotContainKey(oldest);
            assertThat(state.commandResults()).containsKeys(pending, newest);
            assertThat(state.commandResults().get(pending).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.commandResults().get(pending).responseData()).hasSize(fourMiB.length);
            assertThat(state.commandResults().get(pending).retentionSequence()).isEqualTo(pendingRetention);
            try (TradingCoreRuntime restored = TradingCoreRuntimeRestoreTestSupport.restore(
                    ProductLine.SPOT, state.appliedCommandCount(),
                    state.probeValue(), state.commandResults(), state.lastSourceSequences(), state.tradingState())) {
                assertThat(restored.stateHash()).isEqualTo(state.stateHash());
            }
        }
    }

    private static CoreMessage probe(UUID commandId, long sourceSequence, long delta) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PROBE_INCREMENT, commandId,
                ProductLine.SPOT, CommandSource.GATEWAY, 7, sourceSequence, 1001,
                1_000 + sourceSequence, sourceSequence), CoreProtocol.probePayload(delta));
    }

    private static CommandResultLedger.StoredResult stored(
            ResponseStatus status,
            CoreResultCode resultCode,
            long appliedCommandCount,
            long resultIdentity,
            byte[] response,
            long retentionSequence) {
        long sourceSequence = Math.max(1, appliedCommandCount);
        CoreMessage command = probe(new UUID(appliedCommandCount, resultIdentity), sourceSequence, 1);
        return new CommandResultLedger.StoredResult(
                com.surprising.aeron.protocol.CommandFingerprint.of(command), status, resultCode,
                appliedCommandCount, response, retentionSequence);
    }

    private static CoreMessage commandResultQuery(UUID commandId) {
        return new CoreMessage(CoreMessageHeader.query(CoreMessageType.COMMAND_RESULT_QUERY,
                UUID.randomUUID(), ProductLine.SPOT, CommandSource.GATEWAY, 7, 0, 1001, 9_000, 99),
                CoreStateQueryCodec.encodeCommandResultQuery(commandId));
    }
}
