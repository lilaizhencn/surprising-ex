package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.snapshot.*;


import com.surprising.aeron.service.state.index.TriggerOrderIndex;

import com.surprising.aeron.service.state.model.CoreMarkPriceState;
import com.surprising.aeron.service.state.model.CoreRiskState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.ApplyMarkPriceCommand;
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
    void executionTotalSurvivesCheckpointAndCommitMetadata() {
        var reducer = new RuntimeTestStateTransitions();
        var empty = reducer.adjustBalance(reducer.registerInstrument(TradingCoreState.empty(ProductLine.SPOT),
                CoreStateTestFixtures.instrument(ProductLine.SPOT, "BTC-USDT", "BTC", "USDT", "USDT")),
                7, new BalanceAdjustmentCommand("USDT", 50_000));
        var runtimeOrder = CoreStateTestFixtures.order(11, 7, 5, 10)
                .withFill(3, 7, 4, com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN, 2)
                .withExecutionValue(1, -1).snapshot().withCommitMetadata(100, 200);
        var order = new com.surprising.aeron.service.state.model.CoreOrderState(11, ProductLine.SPOT, 7,
                "BTC-USDT", CoreOrderSide.BUY, 5, 5, 10, 3, 7, false,
                com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "", new java.util.UUID(0, 11),
                0, 0, 4, runtimeOrder.executedValueHigh(), runtimeOrder.executedValueLow(),
                100, 100, 200, com.surprising.aeron.service.state.model.CoreOrderStatus.OPEN, 2);
        var state = new TradingCoreState(empty.productLine(), 2, empty.users(), Map.of(11L, order),
                empty.instruments(), empty.riskState(), empty.treasuryState());
        var restored = TradingStateSnapshotCodec.decode(TradingStateSnapshotCodec.encode(state), ProductLine.SPOT);
        assertThat(restored.order(11).executedValueHigh()).isEqualTo(1);
        assertThat(restored.order(11).executedValueLow()).isEqualTo(-1);
        assertThat(restored.businessStateHash()).isEqualTo(state.businessStateHash());
    }

    @Test
    void roundTripPreservesBusinessAndEntityHashes() {
        RuntimeTestStateTransitions reducer = new RuntimeTestStateTransitions();
        TradingCoreState state = reducer.adjustBalance(
                reducer.registerInstrument(TradingCoreState.empty(ProductLine.OPTION),
                        CoreStateTestFixtures.instrument(ProductLine.OPTION,
                                "BTC-OPTION", "BTC", "USDT", "USDT")), 7,
                new BalanceAdjustmentCommand("USDT", 50_000));
        state = reducer.applyMarkPrice(state,
                new ApplyMarkPriceCommand("BTC-OPTION", 500, 1_000, 1_000, 1, 1_000));
        state = reducer.placeOrder(state, 7, new PlaceOrderCommand(71, "BTC-OPTION", CoreOrderSide.BUY, 500, 2, false, com.surprising.aeron.protocol.CoreMarginMode.CROSS, com.surprising.aeron.protocol.CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTX, true, "option-client-71"));

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
                Map.of("BTC-USDT", new CoreMarkPriceState("BTC-USDT", 70_000, 7, 1_000)),
                Map.of(), Map.of(), Map.of("BTC-USDT", scan), 1);
        TradingCoreState state = new TradingCoreState(empty.productLine(), empty.revision(), empty.users(),
                empty.orders(), empty.instruments(), risk, empty.treasuryState(),
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
                state.orders(), state.instruments(), state.riskState(), state.treasuryState(),
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
