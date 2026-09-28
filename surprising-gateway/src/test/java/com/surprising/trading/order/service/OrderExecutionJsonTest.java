package com.surprising.trading.order.service;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class OrderExecutionJsonTest {
    @Test
    void queryAndWebsocketOrderExposeExactExecutionValueAndFractionalAverage() {
        var order = new CoreOrderStateView(71, ProductLine.LINEAR_PERPETUAL, 7, "49",
                CoreOrderSide.BUY, 120, 4, 3, 1, false, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false,
                "partial", new UUID(0, 71), 0, 20, 7, 0, 310, 100, 110, 99, "OPEN", 2);
        var mapper = new ObjectMapper();
        var websocket = mapper.readTree(mapper.writeValueAsString(CoreStateQueryCodec.decodeOrderState(
                CoreStateQueryCodec.encodeOrderState(order))));
        var query = mapper.readTree(mapper.writeValueAsString(order));
        assertThat(websocket).isEqualTo(query);
        assertThat(query.get("executedValueTicks").asString()).isEqualTo("310");
        assertThat(query.get("averagePriceTicks").asString()).isEqualTo("103.333333333333333333");
        assertThat(query.get("cumulativeFeeUnits").asLong()).isEqualTo(7);
        assertThat(query.get("createdAtEpochMillis").asLong()).isEqualTo(100);
    }
}
