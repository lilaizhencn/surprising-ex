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
                CoreTriggerOrderStatus.PENDING, 0, 0, 0, "", "trace", 0, 0, 1_000, 1_000, 1);
        assertThat(CoreTriggerOrderCodec.decodeState(CoreTriggerOrderCodec.encodeState(state))).isEqualTo(state);
        assertThat(CoreTriggerOrderCodec.decodeList(CoreTriggerOrderCodec.encodeList(List.of(state))))
                .containsExactly(state);
        CoreTriggerOrderQuery query = new CoreTriggerOrderQuery(0, "btc-usdt", 501, 10);
        assertThat(CoreTriggerOrderCodec.decodeQuery(CoreTriggerOrderCodec.encodeQuery(query))).isEqualTo(query);
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
}
