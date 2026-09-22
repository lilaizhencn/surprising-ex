package com.surprising.trading.matching;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.*;
import com.surprising.trading.matching.controller.MarketDataController;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import com.surprising.trading.order.service.OrderAeronGateway;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MarketDataLocalTest {
    @Test
    void queriesSharedCoreClientAndPreservesBookShape() {
        var client = mock(AeronClientPool.class);
        var view = new CoreOrderBookView(123, List.of(
                new CoreBookLevelView("BTC-USDT", CoreOrderSide.BUY, 100, 8, 2),
                new CoreBookLevelView("BTC-USDT", CoreOrderSide.SELL, 101, 9, 3)));
        when(client.query(eq(CoreMessageType.BOOK_STATE_QUERY), any(), eq(0L), any()))
                .thenReturn(new CoreResponse(ResponseStatus.OK, 123, CoreStateQueryCodec.encodeOrderBookView(view)));
        var controller = new MarketDataController(new MatchingMarketDataService(new OrderAeronGateway(client)));
        var book = controller.orderBook(" btc-usdt ", 30);
        assertThat(book.symbol()).isEqualTo("BTC-USDT");
        assertThat(book.bids()).hasSize(1);
        assertThat(book.asks()).hasSize(1);
        assertThat(book.bids().getFirst().priceTicks()).isEqualTo(100);
        assertThat(book.asks().getFirst().quantitySteps()).isEqualTo(9);
        var payload = ArgumentCaptor.forClass(byte[].class);
        verify(client).query(eq(CoreMessageType.BOOK_STATE_QUERY), any(), eq(0L), payload.capture());
        assertThat(CoreStateQueryCodec.decodeOrderBookQuery(payload.getValue())).isEqualTo(new CoreOrderBookQuery("BTC-USDT", 30));
        verifyNoMoreInteractions(client);
    }

    @Test
    void invalidDepthIsRejectedBeforeCallingCore() {
        var client = mock(AeronClientPool.class);
        var controller = new MarketDataController(new MatchingMarketDataService(new OrderAeronGateway(client)));
        assertThatThrownBy(() -> controller.orderBook("BTC-USDT", 101))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("400");
        verifyNoInteractions(client);
    }

    @Test
    void coreQueryFailureIsNotAnEmptyBook() {
        var client = mock(AeronClientPool.class);
        when(client.query(eq(CoreMessageType.BOOK_STATE_QUERY), any(), eq(0L), any()))
                .thenReturn(new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.INVALID_COMMAND, 0, new byte[0]));
        var service = new MatchingMarketDataService(new OrderAeronGateway(client));
        assertThatThrownBy(() -> service.orderBookSnapshot("BTC-USDT", 30))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INVALID_COMMAND");
    }
}
