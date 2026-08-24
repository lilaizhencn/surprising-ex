package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
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

    private static CoreMessage command(CoreMessageType type, UUID commandId, long sourceSequence, byte[] payload) {
        return new CoreMessage(CoreMessageHeader.command(type, commandId, ProductLine.SPOT,
                CommandSource.GATEWAY, 7, sourceSequence, 1001, 1_000, sourceSequence), payload);
    }
}
