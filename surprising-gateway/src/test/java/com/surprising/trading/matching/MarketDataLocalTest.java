package com.surprising.trading.matching;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.ValkeyMarketData;
import com.surprising.trading.matching.controller.MarketDataInternalController;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class MarketDataLocalTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void queriesRealtimeAndPublishesBestPricesFirst(ProductLine product) {
        var realtime = mock(ValkeyMarketData.class);
        var view = new CoreOrderBookView(321, List.of(
                new CoreBookLevelView("1", CoreOrderSide.BUY, 98, 8, 2),
                new CoreBookLevelView("1", CoreOrderSide.BUY, 100, 10, 4),
                new CoreBookLevelView("1", CoreOrderSide.BUY, 99, 9, 3),
                new CoreBookLevelView("1", CoreOrderSide.SELL, 103, 13, 7),
                new CoreBookLevelView("1", CoreOrderSide.SELL, 101, 11, 5),
                new CoreBookLevelView("1", CoreOrderSide.SELL, 102, 12, 6)));
        when(realtime.requireBook(product, "1")).thenReturn(new ValkeyMarketData.Book(view, 1234));
        var controller = new MarketDataInternalController(new MatchingMarketDataService(realtime, product));
        var book = controller.orderBook("1", 2);
        assertThat(book.instrumentId()).isEqualTo("1");
        assertThat(book.sequence()).isEqualTo(321);
        assertThat(book.depth()).isEqualTo(2);
        assertThat(book.eventTime().toEpochMilli()).isEqualTo(1234);
        assertThat(book.bids()).extracting(com.surprising.trading.api.model.OrderBookLevel::priceTicks)
                .containsExactly(100L, 99L);
        assertThat(book.asks()).extracting(com.surprising.trading.api.model.OrderBookLevel::priceTicks)
                .containsExactly(101L, 102L);
        verify(realtime).requireBook(product, "1");
        verifyNoMoreInteractions(realtime);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void emptyBookIsAuthoritativeAndAdvertisesTheAvailableWindow(ProductLine product) {
        var realtime = mock(ValkeyMarketData.class);
        when(realtime.requireBook(product, "1"))
                .thenReturn(new ValkeyMarketData.Book(new CoreOrderBookView(1, List.of()), 1234));
        var book = new MatchingMarketDataService(realtime, product).orderBookSnapshot("1", 100);
        assertThat(book.depth()).isEqualTo(50);
        assertThat(book.bids()).isEmpty();
        assertThat(book.asks()).isEmpty();
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void invalidInputIsRejectedBeforeReadingRealtime(ProductLine product) {
        var realtime = mock(ValkeyMarketData.class);
        var controller = new MarketDataInternalController(new MatchingMarketDataService(realtime, product));
        assertThatThrownBy(() -> controller.orderBook("1", 101))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        assertThatThrownBy(() -> controller.orderBook("BTC-USDT", 20))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("400");
        verifyNoInteractions(realtime);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void unavailableRealtimeIsNotAnEmptyBookOrCoreQuery(ProductLine product) {
        var realtime = mock(ValkeyMarketData.class);
        when(realtime.requireBook(product, "1")).thenThrow(
                new com.surprising.realtime.api.exception.ReadViewUnavailableException("MARKET_BOOK_UNAVAILABLE"));
        var controller = new MarketDataInternalController(new MatchingMarketDataService(realtime, product));
        assertThatThrownBy(() -> controller.orderBook("1", 30))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("503");
        verifyNoMoreInteractions(ignoreStubs(realtime));
    }
}
