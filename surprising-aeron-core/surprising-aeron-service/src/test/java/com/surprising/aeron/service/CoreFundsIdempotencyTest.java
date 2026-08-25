package com.surprising.aeron.service;

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
    void balanceAdjustmentRemainsIdempotentAfterItsCommandResultIsEvicted() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            UUID adjustmentId = UUID.randomUUID();
            byte[] payload = TradingCommandCodec.encodeBalanceAdjustment(
                    new BalanceAdjustmentCommand("USDT", 5_000));
            assertThat(state.apply(command(CoreMessageType.ADJUST_BALANCE, adjustmentId, 1, payload)).status())
                    .isEqualTo(ResponseStatus.APPLIED);
            for (long sequence = 2; sequence <= CoreProbeState.MAX_IDEMPOTENCY_RESULTS + 2L; sequence++) {
                assertThat(state.apply(command(CoreMessageType.PROBE_INCREMENT, UUID.randomUUID(), sequence,
                        CoreProtocol.probePayload(1))).status()).isEqualTo(ResponseStatus.APPLIED);
            }

            CoreMessage retry = command(CoreMessageType.ADJUST_BALANCE, adjustmentId,
                    CoreProbeState.MAX_IDEMPOTENCY_RESULTS + 3L, payload);
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot())) {
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
                "FUNDING", "USDT_PERPETUAL", "USDT", 250L, "transfer-7001", "allocation");
        byte[] transferPayload = TradingCommandCodec.encodeTransferFunds(transfer);
        UUID outId = UUID.randomUUID();
        try (CoreProbeState source = new CoreProbeState(ProductLine.SPOT)) {
            source.apply(command(ProductLine.SPOT, CoreMessageType.ADJUST_BALANCE, UUID.randomUUID(),
                    1, TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 5_000))));
            CoreMessage out = command(ProductLine.SPOT, CoreMessageType.TRANSFER_OUT, outId, 2, transferPayload);

            assertThat(source.apply(out).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(source.apply(out).status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(source.tradingState().user(1001).totalUnits("USDT")).isEqualTo(4_750L);
            assertThat(source.pendingTransfers()).hasSize(1);

            try (CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, source.snapshot())) {
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
        try (CoreProbeState target = new CoreProbeState(ProductLine.LINEAR_PERPETUAL)) {
            CoreMessage in = command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.TRANSFER_IN,
                    inId, 1, transferPayload);
            assertThat(target.apply(in).status()).isEqualTo(ResponseStatus.APPLIED);
            assertThat(target.apply(in).status()).isEqualTo(ResponseStatus.DUPLICATE);
            assertThat(target.tradingState().user(1001).totalUnits("USDT")).isEqualTo(250L);

            var changed = new TransferFundsCommand(7001L, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                    "FUNDING", "USDT_PERPETUAL", "USDT", 251L, "transfer-7001", "allocation");
            assertThat(target.apply(command(ProductLine.LINEAR_PERPETUAL, CoreMessageType.TRANSFER_IN,
                    inId, 2, TradingCommandCodec.encodeTransferFunds(changed))).resultCode().name())
                    .isEqualTo("IDEMPOTENCY_CONFLICT");
            assertThat(target.tradingState().user(1001).totalUnits("USDT")).isEqualTo(250L);
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
