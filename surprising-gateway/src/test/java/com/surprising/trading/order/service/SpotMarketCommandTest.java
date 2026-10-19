package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.*;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

class SpotMarketCommandTest {
    @ParameterizedTest @EnumSource(OrderSide.class)
    void sourcesTheCorrectRealtimeSideAndPreservesMarketOrderType(OrderSide side) {
        var gateway = mock(OrderAeronGateway.class);
        var books = mock(MatchingMarketDataService.class);
        var properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.SPOT);
        when(books.orderBookSnapshot("1", 1)).thenReturn(new OrderBookSnapshotResponse("1", 10, 1,
                List.of(new OrderBookLevel(100_000, 10, 1)), List.of(new OrderBookLevel(101_000, 10, 1)), Instant.now()));
        var service = new AeronOrderCommandService(gateway, properties, books);
        service.preflight(new PlaceOrderRequest(1, "spot-market", "1", side, OrderType.MARKET,
                TimeInForce.IOC, 0, 2, MarginMode.CROSS, PositionSide.NET, false, false));
        var command = ArgumentCaptor.forClass(PlaceOrderCommand.class);
        verify(gateway).preflight(eq(1L), command.capture());
        assertThat(command.getValue().orderType()).isEqualTo(CoreOrderType.MARKET);
        assertThat(command.getValue().limitPriceTicks()).isEqualTo(MarketPriceProtection.protectedPriceTicks(
                side, side == OrderSide.BUY ? 101_000 : 100_000, properties.getRisk().getMarketMaxSlippagePpm()));
        verifyNoMoreInteractions(gateway);
    }

    @ParameterizedTest @EnumSource(OrderSide.class)
    void emptyOppositeSideRejectsBeforeAnyCoreCommand(OrderSide side) {
        var gateway = mock(OrderAeronGateway.class);
        var books = mock(MatchingMarketDataService.class);
        var properties = new TradingOrderProperties(); properties.getKafka().setProductLine(ProductLine.SPOT);
        when(books.orderBookSnapshot("1", 1)).thenReturn(new OrderBookSnapshotResponse("1", 1, 1, List.of(), List.of(), Instant.now()));
        var service = new AeronOrderCommandService(gateway, properties, books);
        assertThatThrownBy(() -> service.preflight(new PlaceOrderRequest(1, "spot-empty", "1", side,
                OrderType.MARKET, TimeInForce.FOK, 0, 1, MarginMode.CROSS, PositionSide.NET, false, false)))
                .hasMessageContaining("SPOT_MARKET_LIQUIDITY_UNAVAILABLE");
        verifyNoInteractions(gateway);
    }
}
