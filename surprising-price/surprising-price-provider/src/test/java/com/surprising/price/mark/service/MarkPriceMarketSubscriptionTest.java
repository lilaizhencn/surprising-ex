package com.surprising.price.mark.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.surprising.aeron.protocol.*;
import com.surprising.price.api.model.PerpBookTickerEvent;
import com.surprising.price.api.model.PerpTradeEvent;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceEncoding;
import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MarkPriceMarketSubscriptionTest {
    @Test
    void decodesCommittedBookAndTradeAndIsolatesProductLines() {
        var properties = new MarkPriceProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        var service = mock(MarkPriceService.class);
        var encodings = mock(MarkPriceEncodingService.class);
        when(encodings.currentEncoding("BTC-USDT")).thenReturn(new MarkPriceEncoding(1, 10000, 100, 10000, 1));
        var subscription = new MarkPriceMarketSubscription(properties, service, encodings);
        byte[] book = CoreStateQueryCodec.encodeOrderBookView(new CoreOrderBookView(10,
                List.of(new CoreBookLevelView("BTC-USDT", CoreOrderSide.BUY, 10001, 2, 1),
                        new CoreBookLevelView("BTC-USDT", CoreOrderSide.SELL, 10003, 3, 1))));
        subscription.receive(frame(ProductLine.INVERSE_PERPETUAL, RealtimeFrame.Kind.BOOK, book));
        verifyNoInteractions(service, encodings);
        subscription.receive(frame(ProductLine.LINEAR_PERPETUAL, RealtimeFrame.Kind.BOOK, book));
        var bookCapture = ArgumentCaptor.forClass(PerpBookTickerEvent.class);
        verify(service).acceptBookTicker(bookCapture.capture());
        assertThat(bookCapture.getValue().bestBidPrice()).isEqualByComparingTo("100.01");
        assertThat(bookCapture.getValue().bestAskPrice()).isEqualByComparingTo("100.03");
        byte[] trade = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(10002).putLong(5).putLong(7).put((byte) 0).array();
        subscription.receive(frame(ProductLine.LINEAR_PERPETUAL, RealtimeFrame.Kind.TRADE, trade));
        var tradeCapture = ArgumentCaptor.forClass(PerpTradeEvent.class);
        verify(service).acceptTrade(tradeCapture.capture());
        assertThat(tradeCapture.getValue().price()).isEqualByComparingTo("100.02");
        assertThat(tradeCapture.getValue().quantity()).isEqualByComparingTo("0.0005");
        assertThat(tradeCapture.getValue().tradeTime().toEpochMilli()).isEqualTo(123456);
    }

    private RealtimeFrame frame(ProductLine product, RealtimeFrame.Kind kind, byte[] payload) {
        return new RealtimeFrame(product, kind, 0, 100, 1, 123456, 0, "BTC-USDT", "trade-1", payload);
    }
}
