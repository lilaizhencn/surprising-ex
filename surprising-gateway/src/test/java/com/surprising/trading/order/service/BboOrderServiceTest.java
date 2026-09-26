package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

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
        var gateway = mock(OrderAeronGateway.class);
        var commands = mock(AeronOrderCommandService.class);
        var validator = mock(OrderValidator.class);
        var config = new TradingOrderProperties();
        config.getKafka().setProductLine(product);
        var service = new OrderService(config, validator, null, commands, null, gateway);
        var levels = new ArrayList<CoreBookLevelView>();
        for (int i = 0; i < 5; i++) {
            levels.add(new CoreBookLevelView("BTC-USDT", CoreOrderSide.BUY, 96 + i, 10, 1));
            levels.add(new CoreBookLevelView("BTC-USDT", CoreOrderSide.SELL, 105 - i, 10, 1));
        }
        when(gateway.orderBook(any())).thenReturn(new CoreOrderBookView(1, levels));
        when(validator.validate(any())).thenReturn(ValidationResult.ok(1));
        for (var side : OrderSide.values()) for (var mode : BboPriceMode.values()) {
            var request = request(side, mode, OrderType.LIMIT, 0);
            service.place(request);
            boolean buyBook = (side == OrderSide.BUY) == mode.sameSide();
            long expected = buyBook ? 101 - mode.depth() : 100 + mode.depth();
            verify(commands).place(argThat(r -> r.side() == side && r.priceTicks() == expected
                    && r.bboPriceMode() == null && r.quantitySteps() == 2 && r.orderType() == OrderType.LIMIT), any());
        }
        verify(gateway, times(4)).orderBook(new CoreOrderBookQuery("BTC-USDT", 1));
        verify(gateway, times(4)).orderBook(new CoreOrderBookQuery("BTC-USDT", 5));
    }

    @ParameterizedTest @EnumSource(ProductLine.class)
    void rejectsMissingDepthAndConflictingPriceWithoutPlacingOrder(ProductLine product) {
        var gateway = mock(OrderAeronGateway.class);
        var commands = mock(AeronOrderCommandService.class);
        var validator = mock(OrderValidator.class);
        var config = new TradingOrderProperties(); config.getKafka().setProductLine(product);
        var service = new OrderService(config, validator, null, commands, null, gateway);
        when(gateway.orderBook(any())).thenReturn(new CoreOrderBookView(1, List.of()));
        assertThatThrownBy(() -> service.place(request(OrderSide.BUY, BboPriceMode.OPPONENT_5, OrderType.LIMIT, 0)))
                .hasMessageContaining("BBO_DEPTH_UNAVAILABLE");
        assertThatThrownBy(() -> service.place(request(OrderSide.BUY, BboPriceMode.OPPONENT_1, OrderType.MARKET, 0)))
                .hasMessageContaining("BBO requires a LIMIT");
        assertThatThrownBy(() -> service.place(request(OrderSide.BUY, BboPriceMode.SAME_SIDE_1, OrderType.LIMIT, 1)))
                .hasMessageContaining("priceTicks=0");
        verifyNoInteractions(commands, validator);
    }

    private PlaceOrderRequest request(OrderSide side, BboPriceMode mode, OrderType type, long price) {
        return new PlaceOrderRequest(1, "bbo-" + side + mode, "BTC-USDT", side, type, TimeInForce.GTC,
                price, 2, MarginMode.CROSS, PositionSide.NET, false, false, mode);
    }
}
