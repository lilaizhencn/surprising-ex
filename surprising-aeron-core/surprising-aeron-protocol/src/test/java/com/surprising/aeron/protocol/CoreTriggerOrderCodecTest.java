package com.surprising.aeron.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.Test;

class CoreTriggerOrderCodecTest {
    @Test
    void roundTripsTriggerOrderStateAndQueries() {
        CoreTriggerOrderStateView state = new CoreTriggerOrderStateView(501, ProductLine.LINEAR_PERPETUAL, 1001,
                "tp-501", "oco-1", "BTC-USDT", CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT,
                CoreTriggerCondition.GREATER_OR_EQUAL, 70_000, 0, 0, 0, 0, 0, CoreOrderType.MARKET,
                CoreTimeInForce.IOC, 0, 10, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", "trace", 0, 0, 1_000, 1_000, 1,
                7, -25, 40);
        assertThat(CoreTriggerOrderCodec.decodeState(CoreTriggerOrderCodec.encodeState(state))).isEqualTo(state);
        assertThat(CoreTriggerOrderCodec.decodeList(CoreTriggerOrderCodec.encodeList(List.of(state))))
                .containsExactly(state);
        CoreTriggerOrderQuery query = new CoreTriggerOrderQuery(0, "btc-usdt", 501, 10);
        assertThat(CoreTriggerOrderCodec.decodeQuery(CoreTriggerOrderCodec.encodeQuery(query))).isEqualTo(query);
        CoreTriggerOrderQuery expiryQuery = new CoreTriggerOrderQuery(0, "", 0, 10,
                CoreTriggerOrderStatus.PENDING, 1_700_000_000_000L);
        assertThat(CoreTriggerOrderCodec.decodeQuery(CoreTriggerOrderCodec.encodeQuery(expiryQuery)))
                .isEqualTo(expiryQuery);
    }

    @Test
    void rejectsTruncatedTriggerState() {
        CoreTriggerOrderStateView state = new CoreTriggerOrderStateView(501, ProductLine.LINEAR_PERPETUAL, 1001,
                "", "", "BTC-USDT", CoreOrderSide.SELL, CoreTriggerOrderType.STOP_LOSS,
                CoreTriggerCondition.LESS_OR_EQUAL, 60_000, 0, 0, 0, 0, 0, CoreOrderType.MARKET,
                CoreTimeInForce.IOC, 0, 10, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", "", 0, 0, 1_000, 1_000, 1);
        byte[] encoded = CoreTriggerOrderCodec.encodeState(state);
        assertThatThrownBy(() -> CoreTriggerOrderCodec.decodeState(
                java.util.Arrays.copyOf(encoded, encoded.length - 1)))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void roundTripsLifecycleCommand() {
        assertThat(CoreTriggerOrderCodec.decodeLifecycle(
                CoreTriggerOrderCodec.encodeLifecycle(501, 1_700_000_000_000L)))
                .containsExactly(501L, 1_700_000_000_000L);
        assertThat(CoreTriggerOrderCodec.decodeExecute(
                CoreTriggerOrderCodec.encodeExecute(501, 8, 70_000, 1_700_000_000_001L)))
                .containsExactly(501L, 8L, 70_000L, 1_700_000_000_001L);
    }

    @Test
    void materializesCreationTemplateWithClusterTime() {
        CoreTriggerOrderStateView template = new CoreTriggerOrderStateView(501,
                ProductLine.LINEAR_PERPETUAL, 1001, "tp-501", "", "BTC-USDT", CoreOrderSide.SELL,
                CoreTriggerOrderType.TAKE_PROFIT, CoreTriggerCondition.GREATER_OR_EQUAL, 70_000, 0, 0,
                0, 0, 0, CoreOrderType.MARKET, CoreTimeInForce.IOC, 0, 10, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", "command-id",
                0, 0, 0, 0, 1);

        CoreTriggerOrderStateView decoded = CoreTriggerOrderCodec.decodeState(
                CoreTriggerOrderCodec.encodeState(template));
        CoreTriggerOrderStateView materialized = decoded.materializeCreation(1_700_000_000_000L);

        assertThat(materialized.createdAtEpochMillis()).isEqualTo(1_700_000_000_000L);
        assertThat(materialized.updatedAtEpochMillis()).isEqualTo(1_700_000_000_000L);
    }
}
