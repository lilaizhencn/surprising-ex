package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.aeron.protocol.CommandSource;
import com.surprising.aeron.protocol.CommandFingerprint;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageHeader;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.state.RuntimeFundsDelta;
import com.surprising.aeron.service.state.RuntimeProjectionPoint;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingMatchingTest {

    @Test
    void preservesCapturedPreCommandHashesAcrossDeferredMatchingUpdates() {
        CoreMessage command = command(11);
        CommandFingerprint fingerprint = CommandFingerprint.of(command);
        TradingCoreState beforeState = TradingCoreState.empty(ProductLine.LINEAR_PERPETUAL);
        RuntimeProjectionPoint beforeProjection = new RuntimeProjectionPoint(0, beforeState);
        RuntimeFundsDelta fundsDelta = RuntimeFundsDelta.empty();
        PendingMatching pending = new PendingMatching(7, PendingMatching.Operation.PLACE, command, fingerprint,
                List.of(17L), beforeProjection, 101L, 202L, fundsDelta);

        PendingMatching updatedCommand = pending.withCommand(command(12));
        PendingMatching updatedCancellations = updatedCommand.withPreMatchingCancellations(List.of(18L, 19L));

        assertThat(updatedCancellations.beforeBusinessStateHash()).isEqualTo(101L);
        assertThat(updatedCancellations.beforeFundsStateHash()).isEqualTo(202L);
        assertThat(updatedCancellations.beforeProjection()).isSameAs(beforeProjection);
        assertThat(updatedCancellations.fundsDelta()).isSameAs(fundsDelta);
        assertThat(updatedCancellations.preMatchingCancellationOrderIds()).containsExactly(18L, 19L);
        assertThat(updatedCancellations.decodedCommand()).isSameAs(updatedCommand.decodedCommand());
        assertThat(updatedCancellations.fingerprint()).isSameAs(fingerprint);
    }

    private static CoreMessage command(long sourceSequence) {
        return new CoreMessage(CoreMessageHeader.command(CoreMessageType.PLACE_ORDER, UUID.randomUUID(),
                ProductLine.LINEAR_PERPETUAL, CommandSource.GATEWAY, 7, sourceSequence,
                101, 1_700_000_000_000L, sourceSequence),
                TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                        1_000 + sourceSequence, "BTC-USDT", 1, CoreOrderSide.BUY, 100, 1,
                        false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                        CoreTimeInForce.GTC, false, "pending-" + sourceSequence)));
    }
}
