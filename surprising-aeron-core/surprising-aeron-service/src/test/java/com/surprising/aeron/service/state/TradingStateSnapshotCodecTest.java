package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ProtocolException;
import com.surprising.aeron.protocol.ReservationKind;
import com.surprising.product.api.ProductLine;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TradingStateSnapshotCodecTest {

    @Test
    void roundTripPreservesBusinessAndEntityHashes() {
        TradingCoreReducer reducer = new TradingCoreReducer();
        TradingCoreState state = reducer.adjustBalance(
                reducer.upsertInstrument(TradingCoreState.empty(ProductLine.OPTION),
                        CoreStateTestFixtures.instrument(ProductLine.OPTION,
                                "BTC-OPTION", "BTC", "USDT", "USDT", 4)), 7,
                new BalanceAdjustmentCommand("USDT", 50_000));
        state = reducer.placeOrder(state, 7, new PlaceOrderCommand(71, "BTC-OPTION", 4, "BTC", "USDT", "USDT",
                CoreOrderSide.BUY, 500, 2, false,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS,
                com.surprising.aeron.protocol.CorePositionSide.NET,
                ReservationKind.DERIVATIVE_MARGIN, "USDT", 1_500,
                CoreOrderType.LIMIT, CoreTimeInForce.GTX, 500, true,
                "option-client-71", -10, 20));

        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(state), ProductLine.OPTION);

        assertThat(restored).isEqualTo(state);
        assertThat(restored.businessStateHash()).isEqualTo(state.businessStateHash());
        assertThat(restored.userStateHash(7)).isEqualTo(state.userStateHash(7));
        assertThat(restored.orderStateHash(71)).isEqualTo(state.orderStateHash(71));
    }

    @Test
    void roundTripPreservesRiskAndTriggerContinuationCursor() {
        TradingCoreState empty = TradingCoreState.empty(ProductLine.SPOT);
        CoreRiskState.RiskScan scan = new CoreRiskState.RiskScan(
                "BTC-USDT", 7, 6, 0, false, 7, 1, "position-key", 9,
                10, 11, 12, 13, false, TriggerOrderIndex.PHASE_TRAILING_LESS_OR_EQUAL,
                400, 300, 500, 70_000, 1_234, 88, 77);
        CoreRiskState risk = new CoreRiskState(
                Map.of("BTC-USDT", new CoreMarkPriceState("BTC-USDT", 1, 70_000, 7)),
                Map.of(), Map.of(), Map.of("BTC-USDT", scan), 1);
        TradingCoreState state = new TradingCoreState(empty.productLine(), empty.revision(), empty.users(),
                empty.orders(), empty.bookState(), empty.instruments(), risk, empty.treasuryState(),
                empty.leverages(), empty.algoOrders(), empty.cancelAllAfterTimers(), empty.clientOrderIndex(),
                empty.triggerOrders());

        TradingCoreState restored = TradingStateSnapshotCodec.decode(
                TradingStateSnapshotCodec.encode(state), ProductLine.SPOT);

        assertThat(restored.riskState().scans().get("BTC-USDT")).isEqualTo(scan);
        assertThat(restored.businessStateHash()).isEqualTo(state.businessStateHash());
    }

    @Test
    void rejectsProductLineMismatchAndTruncation() {
        byte[] encoded = TradingStateSnapshotCodec.encode(TradingCoreState.empty(ProductLine.SPOT));

        assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(encoded, ProductLine.OPTION))
                .isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(
                java.util.Arrays.copyOf(encoded, encoded.length - 1), ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void authoritativeConstructorRejectsMissingClientOrderIndex() {
        TradingCoreState state = TradingCoreState.empty(ProductLine.SPOT);

        assertThatThrownBy(() -> new TradingCoreState(state.productLine(), state.revision(), state.users(),
                state.orders(), state.bookState(), state.instruments(), state.riskState(), state.treasuryState(),
                state.leverages(), state.algoOrders(), state.cancelAllAfterTimers(), null, state.triggerOrders()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("client order index is required");
    }

    @Test
    void rejectsUnsupportedSnapshotVersion() {
        byte[] encoded = TradingStateSnapshotCodec.encode(TradingCoreState.empty(ProductLine.SPOT));
        encoded[0] = 15;
        encoded[1] = 0;
        encoded[2] = 0;
        encoded[3] = 0;

        assertThatThrownBy(() -> TradingStateSnapshotCodec.decode(encoded, ProductLine.SPOT))
                .isInstanceOf(ProtocolException.class)
                .hasMessageContaining("unsupported trading snapshot version");
    }
}
