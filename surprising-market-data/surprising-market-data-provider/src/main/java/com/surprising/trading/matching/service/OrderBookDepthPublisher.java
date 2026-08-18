package com.surprising.trading.matching.service;

import com.surprising.trading.api.model.OrderBookDepthEvent;

/** 发布最新公共订单簿快照，不接入持久化业务 Outbox。 */
public interface OrderBookDepthPublisher {

    OrderBookDepthPublisher NOOP = ignored -> {
    };

    void offer(OrderBookDepthEvent event);
}
