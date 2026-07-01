package com.surprising.trading.matching.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import exchange.core2.core.common.OrderAction;
import org.junit.jupiter.api.Test;

class ExchangeCoreMapperTest {

    @Test
    void mapsSideToExchangeCoreAction() {
        assertThat(ExchangeCoreMapper.action(OrderSide.BUY)).isEqualTo(OrderAction.BID);
        assertThat(ExchangeCoreMapper.action(OrderSide.SELL)).isEqualTo(OrderAction.ASK);
    }

    @Test
    void mapsTimeInForceToExchangeCoreOrderType() {
        assertThat(ExchangeCoreMapper.orderType(OrderType.LIMIT, TimeInForce.GTC))
                .isEqualTo(exchange.core2.core.common.OrderType.GTC);
        assertThat(ExchangeCoreMapper.orderType(OrderType.LIMIT, TimeInForce.IOC))
                .isEqualTo(exchange.core2.core.common.OrderType.IOC);
        assertThat(ExchangeCoreMapper.orderType(OrderType.LIMIT, TimeInForce.FOK))
                .isEqualTo(exchange.core2.core.common.OrderType.FOK);
        assertThat(ExchangeCoreMapper.orderType(OrderType.LIMIT, TimeInForce.GTX))
                .isEqualTo(exchange.core2.core.common.OrderType.GTC);
    }

    @Test
    void mapsMarketOrdersToAggressivePrices() {
        assertThat(ExchangeCoreMapper.effectivePriceTicks(OrderType.MARKET, 0L, 1_000_000L))
                .isEqualTo(1_000_000L);
        assertThat(ExchangeCoreMapper.effectivePriceTicks(OrderType.LIMIT, 123L, 1_000_000L))
                .isEqualTo(123L);
    }
}
