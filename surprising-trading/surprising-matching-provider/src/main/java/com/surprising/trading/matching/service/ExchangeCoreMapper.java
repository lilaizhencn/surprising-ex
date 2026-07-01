package com.surprising.trading.matching.service;

import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.TimeInForce;
import exchange.core2.core.common.OrderAction;

public final class ExchangeCoreMapper {

    private ExchangeCoreMapper() {
    }

    public static OrderAction action(OrderSide side) {
        return side == OrderSide.BUY ? OrderAction.BID : OrderAction.ASK;
    }

    public static exchange.core2.core.common.OrderType orderType(OrderType orderType, TimeInForce timeInForce) {
        if (orderType == OrderType.MARKET) {
            return timeInForce == TimeInForce.FOK
                    ? exchange.core2.core.common.OrderType.FOK
                    : exchange.core2.core.common.OrderType.IOC;
        }
        return switch (timeInForce) {
            case IOC -> exchange.core2.core.common.OrderType.IOC;
            case FOK -> exchange.core2.core.common.OrderType.FOK;
            case GTC, GTX -> exchange.core2.core.common.OrderType.GTC;
        };
    }

    public static long effectivePriceTicks(OrderType orderType, long priceTicks, long protectedMarketPriceTicks) {
        return orderType == OrderType.LIMIT ? priceTicks : protectedMarketPriceTicks;
    }
}
