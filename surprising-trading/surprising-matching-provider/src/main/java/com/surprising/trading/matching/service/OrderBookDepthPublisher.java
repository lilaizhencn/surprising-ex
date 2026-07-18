package com.surprising.trading.matching.service;

import com.surprising.trading.api.model.OrderBookDepthEvent;

/** Publishes the latest public order-book snapshot without joining the durable business outbox. */
public interface OrderBookDepthPublisher {

    OrderBookDepthPublisher NOOP = ignored -> {
    };

    void offer(OrderBookDepthEvent event);
}
