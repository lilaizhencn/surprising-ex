package com.surprising.aeron.service.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CompleteTransferCommand;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreFundsIdempotencyTest {

    @Test
    void newFundsCommandsRemainExecutableBeyondOldRetentionLimit() {
        try (var state = new TradingCoreRuntime(ProductLine.SPOT)) {
            var fingerprint = com.surprising.aeron.protocol.CommandFingerprint.fromBytes(
                    new byte[com.surprising.aeron.protocol.CommandFingerprint.LENGTH]);
            for (int i = 0; i < 131_080; i++) state.terminalRetention().retainFundsCommand(new UUID(77, i), fingerprint);
            var deposit = command(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100)));
            assertThat(state.apply(deposit).status()).isEqualTo(ResponseStatus.APPLIED);
            try (var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.apply(deposit).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(restored.tradingState().user(1001).totalUnits("USDT")).isEqualTo(100);
                var next = command(CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(), 2,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 50)));
                assertThat(restored.apply(next).status()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(restored.tradingState().user(1001).totalUnits("USDT")).isEqualTo(150);
            }
        }
    }

    @Test
    void balanceAdjustmentRemainsIdempotentAfterItsCommandResultIsEvicted() {
        try (TradingCoreRuntime state = new TradingCoreRuntime(ProductLine.SPOT)) {
            UUID adjustmentId = UUID.randomUUID();
            byte[] payload = TradingCommandCodec.encodeBalanceAdjustment(
                    new BalanceAdjustmentCommand("USDT", 5_000));
            assertThat(state.apply(command(CoreMessageType.ADJUST_BALANCE, adjustmentId, 1, payload)).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            for (long sequence = 2; sequence <= TradingCoreRuntime.MAX_IDEMPOTENCY_RESULTS + 2L; sequence++) {
                assertThat(state.apply(command(CoreMessageType.PROBE_INCREMENT, UUID.randomUUID(), sequence,
                        CoreProtocol.probePayload(1))).status()).isEqualTo(ResponseStatus.APPLIED);
            }

            CoreMessage retry = command(CoreMessageType.ADJUST_BALANCE, adjustmentId,
                    TradingCoreRuntime.MAX_IDEMPOTENCY_RESULTS + 3L, payload);
            try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
                assertThat(restored.apply(retry).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(restored.tradingState().user(1001).totalUnits("USDT")).isEqualTo(5_000);
            }

            assertThat(state.apply(retry).status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(state.tradingState().user(1001).totalUnits("USDT")).isEqualTo(5_000);
        }
    }

    @Test
    void transferLifecycleIsIdempotentAndPendingStateSurvivesSnapshot() {
        var transfer = new TransferFundsCommand(7001L, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                "FUNDING", "USDT_PERPETUAL", "USDT", 250L, "transfer-7001", "allocation", 1001L, 1001L);
        byte[] transferPayload = TradingCommandCodec.encodeTransferFunds(transfer);
        UUID outId = UUID.randomUUID();
        try (TradingCoreRuntime source = new TradingCoreRuntime(ProductLine.SPOT)) {
            source.apply(command(ProductLine.SPOT, CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(),
                    1, TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 5_000))));
            CoreMessage out = command(ProductLine.SPOT, CoreMessageType.TRANSFER_OUT, outId, 2, transferPayload);

            assertThat(source.apply(out).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(source.apply(out).status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(source.tradingState().user(1001).totalUnits("USDT")).isEqualTo(4_750L);
            assertThat(source.pendingTransfers()).hasSize(1);

            try (TradingCoreRuntime restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, source.snapshot())) {
                assertThat(restored.pendingTransfers()).hasSize(1);
                CoreMessage complete = command(ProductLine.SPOT, CoreMessageType.COMPLETE_TRANSFER,
                        UUID.randomUUID(), 3, TradingCommandCodec.encodeCompleteTransfer(
                                new CompleteTransferCommand(transfer.transferId())));
                assertThat(restored.apply(complete).status()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(restored.pendingTransfers()).isEmpty();
                assertThat(restored.tradingState().user(1001).totalUnits("USDT")).isEqualTo(4_750L);
            }
        }

        UUID inId = UUID.randomUUID();
        try (TradingCoreRuntime target = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            CoreMessage in = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.TRANSFER_IN,
                    inId, 1, transferPayload);
            assertThat(target.apply(in).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(target.apply(in).status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(target.tradingState().user(1001).totalUnits("USDT")).isEqualTo(250L);

            var changed = new TransferFundsCommand(7001L, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                    "FUNDING", "USDT_PERPETUAL", "USDT", 251L, "transfer-7001", "allocation", 1001L, 1001L);
            assertThat(target.apply(command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.TRANSFER_IN,
                    inId, 2, TradingCommandCodec.encodeTransferFunds(changed))).resultCode().name())
                    .isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(target.tradingState().user(1001).totalUnits("USDT")).isEqualTo(250L);
        }
    }

    @Test
    void publishedTransferV1ReplaysAndV2RetryCannotDebitAgainAfterRecovery() {
        var transfer = new TransferFundsCommand(7002, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                "FUNDING", "USDT_PERPETUAL", "USDT", 250, "old-transfer", "allocation", 1001, 1001);
        byte[] v2 = TradingCommandCodec.encodeTransferFunds(transfer);
        byte[] v1 = new byte[v2.length - 16];
        java.nio.ByteBuffer.wrap(v1).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(1).putLong(7002);
        System.arraycopy(v2, 28, v1, 12, v2.length - 28);
        UUID id = new UUID(7002, 1);
        var oldCommand = command(ProductLine.SPOT, CoreMessageType.TRANSFER_OUT, id, 2, v1);
        var retry = command(ProductLine.SPOT, CoreMessageType.TRANSFER_OUT, id, 2, v2);
        try (var source = new TradingCoreRuntime(ProductLine.SPOT)) {
            source.apply(command(CoreMessageType.ADJUST_BALANCE, new UUID(7002, 0), 1,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 5000))));
            byte[] beforeTail = source.snapshot();
            assertThat(source.apply(oldCommand).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(source.apply(retry).status()).isEqualTo(ResponseStatus.DUPLICATE);
            try (var crashRecovered = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, beforeTail)) {
                assertThat(crashRecovered.apply(oldCommand).status()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(crashRecovered.stateHash()).isEqualTo(source.stateHash());
            }
            for (int i = 3; i < 150; i++) source.apply(command(CoreMessageType.ADJUST_BALANCE,
                    new UUID(7002, i), i, TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 1))));
            try (var restored = TradingCoreRuntime.fromSnapshot(ProductLine.SPOT, source.snapshot())) {
                long before = restored.stateHash();
                assertThat(restored.pendingTransfers().get(7002L).command()).isEqualTo(transfer);
                assertThat(restored.apply(retry).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(restored.apply(oldCommand).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(restored.stateHash()).isEqualTo(before);
                assertThat(restored.tradingState().user(1001).totalUnits("USDT")).isEqualTo(4750 + 147);
                var wrongRecipient = new TransferFundsCommand(7002, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                        "FUNDING", "USDT_PERPETUAL", "USDT", 250, "old-transfer", "allocation", 1001, 1002);
                assertThat(restored.apply(command(ProductLine.SPOT, CoreMessageType.TRANSFER_OUT, id, 151,
                        TradingCommandCodec.encodeTransferFunds(wrongRecipient))).resultCode().name())
                        .isEqualTo("IDEMPOTENCY_CONFLICT");
            }
        }
        try (var target = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            var inbound = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.TRANSFER_IN, id, 1, v1);
            assertThat(target.apply(inbound).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(target.apply(command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.TRANSFER_IN, id, 1, v2)).status())
                    .isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(target.tradingState().user(1001).totalUnits("USDT")).isEqualTo(250);
        }
    }

    private static CoreMessage command(CoreMessageType type, UUID commandId, long sourceSequence, byte[] payload) {
        return command(ProductLine.SPOT, type, commandId, sourceSequence, payload);
    }

    private static CoreMessage command(ProductLine productLine, CoreMessageType type, UUID commandId,
                                       long sourceSequence, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(type, commandId, productLine,
                CommandSource.GATEWAY, 7, sourceSequence, 1001, 1_000, sourceSequence), payload);
    }
}
