package com.surprising.trading.matching.service;

import com.surprising.trading.api.model.PublicTradeEvent;

/** Offers public trades to the non-durable market-data/candlestick path. */
public interface PublicTradePublisher {

    PublicTradePublisher NOOP = event -> { };

    void offer(PublicTradeEvent event);
}
