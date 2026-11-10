package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.*;
import com.surprising.trading.order.config.TradingOrderProperties;
import com.surprising.trading.order.model.ValidationResult;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class BboOrderServiceTest {
    @ParameterizedTest @EnumSource(ProductLine.class)
    void resolvesBothSidesAndAllModesBeforeValidation(ProductLine product) {
        var gateway = mock(com.surprising.trading.matching.service.MatchingMarketDataService.class);
        var aeron = mock(OrderAeronGateway.class);
        var validator = mock(OrderValidator.class);
        var config = new TradingOrderProperties();
        config.getKafka().setProductLine(product);
        var service = new OrderService(config, validator, aeron, null, gateway, null, null, null);
        when(aeron.commandOutcome(eq(CoreMessageType.PLACE_ORDER), any(UUID.class), eq(1L), any(byte[].class)))
                .thenAnswer(inv -> {
                    PlaceOrderCommand cmd = TradingCommandCodec.decodePlaceOrder(inv.getArgument(3));
                    CoreOrderStateView order = new CoreOrderStateView(cmd.orderId(), product, 1L, cmd.instrumentId(),
                            cmd.side(), cmd.limitPriceTicks(), cmd.quantitySteps(), 0, cmd.quantitySteps(),
                            cmd.reduceOnly(), cmd.marginMode(), cmd.positionSide(), cmd.orderType(), cmd.timeInForce(),
                            cmd.postOnly(), cmd.clientOrderId(), UUID.randomUUID(), 0, 0, 1000, 1000, 1, "OPEN", 1);
                    return new CoreCommandOutcome.Terminal(new CoreResponse(ResponseStatus.APPLIED, ResponseStatus.APPLIED,
                            CoreResultCode.NONE, 1, CoreCommandResultCodec.encode(new CoreCommandResultView(List.of(order), List.of()))));
                });
        var levels = new ArrayList<CoreBookLevelView>();
        for (int i = 0; i < 5; i++) {
            levels.add(new CoreBookLevelView("1", CoreOrderSide.BUY, 96 + i, 10, 1));
            levels.add(new CoreBookLevelView("1", CoreOrderSide.SELL, 105 - i, 10, 1));
        }
        when(gateway.orderBookSnapshot(eq("1"), anyInt())).thenReturn(new OrderBookSnapshotResponse("1", 1, 50,
                levels.stream().filter(l -> l.side() == CoreOrderSide.BUY)
                        .map(l -> new OrderBookLevel(l.priceTicks(), l.quantitySteps(), l.orderCount())).toList(),
                levels.stream().filter(l -> l.side() == CoreOrderSide.SELL)
                        .map(l -> new OrderBookLevel(l.priceTicks(), l.quantitySteps(), l.orderCount())).toList(),
                java.time.Instant.now()));
        when(validator.validate(any())).thenReturn(ValidationResult.ok(1));
        for (var side : OrderSide.values()) for (var mode : BboPriceMode.values()) {
            var request = request(side, mode, OrderType.LIMIT, 0);
            OrderResponse placed = service.place(request);
            boolean buyBook = (side == OrderSide.BUY) == mode.sameSide();
            long expected = buyBook ? 101 - mode.depth() : 100 + mode.depth();
            assertThat(placed.side()).isEqualTo(side);
            assertThat(placed.priceTicks()).isEqualTo(expected);
            assertThat(placed.quantitySteps()).isEqualTo(2);
            assertThat(placed.orderType()).isEqualTo(OrderType.LIMIT);
        }
        verify(gateway, times(4)).orderBookSnapshot("1", 1);
        verify(gateway, times(4)).orderBookSnapshot("1", 5);
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void rejectsMissingDepthAndConflictingPriceWithoutPlacingOrder(ProductLine product) {
        var gateway = mock(com.surprising.trading.matching.service.MatchingMarketDataService.class);
        var aeron = mock(OrderAeronGateway.class);
        var validator = mock(OrderValidator.class);
        var config = new TradingOrderProperties(); config.getKafka().setProductLine(product);
        var service = new OrderService(config, validator, aeron, null, gateway, null, null, null);
        when(gateway.orderBookSnapshot(eq("1"), anyInt())).thenReturn(new OrderBookSnapshotResponse("1", 1, 50, List.of(), List.of(), java.time.Instant.now()));
        assertThatThrownBy(() -> service.place(request(OrderSide.BUY, BboPriceMode.OPPONENT_5, OrderType.LIMIT, 0)))
                .hasMessageContaining("BBO_DEPTH_UNAVAILABLE");
        assertThatThrownBy(() -> service.place(request(OrderSide.BUY, BboPriceMode.OPPONENT_1, OrderType.MARKET, 0)))
                .hasMessageContaining("BBO requires a LIMIT");
        assertThatThrownBy(() -> service.place(request(OrderSide.BUY, BboPriceMode.SAME_SIDE_1, OrderType.LIMIT, 1)))
                .hasMessageContaining("priceTicks=0");
        verifyNoInteractions(aeron, validator);
    }

    private PlaceOrderRequest request(OrderSide side, BboPriceMode mode, OrderType type, long price) {
        return new PlaceOrderRequest(1, "bbo-" + side + mode, "1", side, type, TimeInForce.GTC,
                price, 2, MarginMode.CROSS, PositionSide.NET, false, false, mode);
    }
}
