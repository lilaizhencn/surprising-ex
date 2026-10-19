package com.surprising.websocket.provider.service;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.nio.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.*;

class RealtimePublicTradeTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void preservesTheReliableHistoryIdentityAndKeepsSequenceDomainsSeparate(ProductLine product) {
        String id = product + ":9007199254740993:123:456:0";
        var payload = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(100).putLong(20).putLong(123).put((byte) CoreOrderSide.SELL.ordinal()).array();
        var frame = new RealtimeFrame(product, RealtimeFrame.Kind.TRADE, 0, 9007199254740993L,
                1, 1234, 0, "1", id, payload);
        var trade = RealtimeWebSocketBridge.publicTrade(frame);
        assertThat(trade).containsEntry("tradeId", id).containsEntry("instrumentId", "1")
                .containsEntry("priceTicks", 100L).containsEntry("quantitySteps", 20L)
                .containsEntry("coreSequence", "123").containsEntry("side", "SELL")
                .containsEntry("eventTime", "1970-01-01T00:00:01.234Z");
        assertThat(trade).doesNotContainKey("sequence");
    }
}
