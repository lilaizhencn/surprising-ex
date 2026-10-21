package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.orchestration.pipeline.CoreTestCompletion;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CoreCommandFingerprintTest {

    @Test
    void changedPayloadWithSameCommandIdConflictsBeforeAnyMutation() {
        try (var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            UUID commandId = UUID.randomUUID();
            CoreMessage first = probe(commandId, 1, 7, 1_000, 11);
            CoreMessage changed = probe(commandId, 2, 99, 9_999, 12);

            assertThat(CoreTestCompletion.applyAsynchronously(state, first).status()).isEqualTo(ResponseStatus.APPLIED);
            CoreResponse conflict = CoreTestCompletion.applyAsynchronously(state, changed);

            assertThat(conflict.resultCode().name()).isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(testBalance(state, 1001)).isEqualTo(7);
            assertThat(state.appliedCommandCount()).isOne();
            assertThat(state.lastSourceSequences())
                    .containsEntry(new TradingCoreRuntime.SourceKey(CommandSource.GATEWAY, 7), 1L);
        }
    }

    @Test
    void sameFingerprintReplaysOriginalAfterSourceEpochAndSnapshotRestore() {
        TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT);
        UUID commandId = UUID.randomUUID();
        CoreResponse original = CoreTestCompletion.applyAsynchronously(state, probe(commandId, 1, 7, 1_000, 11));
        byte[] snapshot = state.snapshot();
        TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, snapshot);
        long stateHash = restored.stateHash();
        long appliedCommandCount = restored.appliedCommandCount();
        Map<TradingCoreRuntime.SourceKey, Long> sourceSequences = restored.lastSourceSequences();

        CoreResponse replay = CoreTestCompletion.applyAsynchronously(restored, new CoreMessage(
                CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, commandId, ProductLine.SPOT,
                        CommandSource.GATEWAY, 999, 4_000, 1001, 8_000, 44),
                com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 7))));

        assertThat(replay.status()).isEqualTo(ResponseStatus.DUPLICATE);
        assertThat(replay.commandStatus()).isEqualTo(original.commandStatus());
        assertThat(replay.resultCode()).isEqualTo(original.resultCode());
        assertThat(replay.appliedCommandCount()).isEqualTo(original.appliedCommandCount());
        assertThat(replay.data()).containsExactly(original.data());
        assertThat(restored.stateHash()).isEqualTo(stateHash);
        assertThat(restored.appliedCommandCount()).isEqualTo(appliedCommandCount);
        assertThat(restored.lastSourceSequences()).isEqualTo(sourceSequences);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(ProductLine.class)
    void preparedFingerprintIsRetainedAndConflictsSurviveSnapshot(ProductLine product) {
        UUID id = UUID.randomUUID();
        CoreMessage message = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                id, product, CommandSource.GATEWAY, 7, 1, 1001, 1000, 11), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 7)));
        var fingerprint = com.surprising.aeron.protocol.CommandFingerprint.of(message);
        try (var state = new TradingCoreRuntime(product)) {
            var response = state.applyDecodedCommand(message, 1000, 1, null, false, fingerprint);
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (state.directCommand.active()) {
                response = state.directCommand.poll(state);
                if (System.nanoTime() >= deadline) throw new AssertionError("funds command did not complete");
            }
            assertThat(response.status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(state.resultLedger.get(id).fingerprint()).isSameAs(fingerprint);
            try (var restored = TradingCoreRuntime.fromSnapshot(product, state.snapshot())) {
                assertThat(CoreTestCompletion.applyAsynchronously(restored, message).status()).isEqualTo(ResponseStatus.DUPLICATE);
                var changed = new CoreMessage(message.header(), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 99)));
                assertThat(restored.applyDecodedCommand(changed, 1000, 2, null, false,
                        com.surprising.aeron.protocol.CommandFingerprint.of(changed)).resultCode().name())
                        .isEqualTo("IDEMPOTENCY_CONFLICT");
                assertThat(testBalance(restored, 1001)).isEqualTo(7);
            }
            var next = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                    UUID.randomUUID(), product, CommandSource.GATEWAY, 7, 2, 1001, 1001, 12),
                    com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", 3)));
            assertThat(CoreTestCompletion.applyAsynchronously(state, next).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(testBalance(state, 1001)).isEqualTo(10);
        }
    }

    private static CoreMessage probe(UUID commandId, long sourceSequence, long delta,
                                     long submittedAt, long correlationId) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, commandId,
                ProductLine.SPOT, CommandSource.GATEWAY, 7, sourceSequence, 1001,
                submittedAt, correlationId), com.surprising.aeron.protocol.TradingCommandCodec.encodeBalanceAdjustment(new com.surprising.aeron.protocol.BalanceAdjustmentCommand("USDT", delta)));
    }
    private static long testBalance(TradingCoreRuntime state, long userId) {
        var user = state.tradingState().user(userId);
        return user == null ? 0 : user.totalUnits("USDT");
    }
}
