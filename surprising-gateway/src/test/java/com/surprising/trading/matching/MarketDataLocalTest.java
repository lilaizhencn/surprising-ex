package com.surprising.trading.matching;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.*;
import com.surprising.trading.matching.controller.MarketDataInternalController;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import com.surprising.trading.order.service.OrderAeronGateway;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MarketDataLocalTest {
    @Test
    void publishesBestPricesFirstRegardlessOfCoreSerializationOrder() {
        var client = mock(AeronClientPool.class);
        var view = new CoreOrderBookView(321, List.of(
                new CoreBookLevelView("1", CoreOrderSide.BUY, 98, 8, 2),
                new CoreBookLevelView("1", CoreOrderSide.BUY, 99, 9, 3),
                new CoreBookLevelView("1", CoreOrderSide.BUY, 100, 10, 4),
                new CoreBookLevelView("1", CoreOrderSide.SELL, 103, 13, 7),
                new CoreBookLevelView("1", CoreOrderSide.SELL, 101, 11, 5),
                new CoreBookLevelView("1", CoreOrderSide.SELL, 102, 12, 6)));
        when(client.query(eq(CoreMessageType.BOOK_STATE_QUERY), any(), eq(0L), any()))
                .thenReturn(new CoreResponse(ResponseStatus.OK, 321, CoreStateQueryCodec.encodeOrderBookView(view)));
        var book = new MatchingMarketDataService(new OrderAeronGateway(client)).orderBookSnapshot("1", 3);
        assertThat(book.sequence()).isEqualTo(321);
        assertThat(book.bids()).extracting(com.surprising.trading.api.model.OrderBookLevel::priceTicks)
                .containsExactly(100L, 99L, 98L);
        assertThat(book.asks()).extracting(com.surprising.trading.api.model.OrderBookLevel::priceTicks)
                .containsExactly(101L, 102L, 103L);
        assertThat(book.bids().getFirst().quantitySteps()).isEqualTo(10);
        assertThat(book.asks().getFirst().orderCount()).isEqualTo(5);
    }

    @Test
    void queriesSharedCoreClientAndPreservesBookShape() {
        var client = mock(AeronClientPool.class);
        var view = new CoreOrderBookView(123, List.of(
                new CoreBookLevelView("1", CoreOrderSide.BUY, 100, 8, 2),
                new CoreBookLevelView("1", CoreOrderSide.SELL, 101, 9, 3)));
        when(client.query(eq(CoreMessageType.BOOK_STATE_QUERY), any(), eq(0L), any()))
                .thenReturn(new CoreResponse(ResponseStatus.OK, 123, CoreStateQueryCodec.encodeOrderBookView(view)));
        var controller = new MarketDataInternalController(new MatchingMarketDataService(new OrderAeronGateway(client)));
        var book = controller.orderBook("1", 30);
        assertThat(book.instrumentId()).isEqualTo("1");
        assertThat(book.bids()).hasSize(1);
        assertThat(book.asks()).hasSize(1);
        assertThat(book.bids().getFirst().priceTicks()).isEqualTo(100);
        assertThat(book.asks().getFirst().quantitySteps()).isEqualTo(9);
        var payload = ArgumentCaptor.forClass(byte[].class);
        verify(client).query(eq(CoreMessageType.BOOK_STATE_QUERY), any(), eq(0L), payload.capture());
        assertThat(CoreStateQueryCodec.decodeOrderBookQuery(payload.getValue())).isEqualTo(new CoreOrderBookQuery("1", 30));
        verifyNoMoreInteractions(client);
    }

    @Test
    void invalidDepthIsRejectedBeforeCallingCore() {
        var client = mock(AeronClientPool.class);
        var controller = new MarketDataInternalController(new MatchingMarketDataService(new OrderAeronGateway(client)));
        assertThatThrownBy(() -> controller.orderBook("1", 101))
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
        assertThatThrownBy(() -> service.orderBookSnapshot("1", 30))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INVALID_COMMAND");
    }
}
