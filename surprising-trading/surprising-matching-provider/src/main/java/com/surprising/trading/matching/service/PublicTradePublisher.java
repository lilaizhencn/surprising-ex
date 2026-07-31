package com.surprising.trading.matching.service;

import com.surprising.trading.api.model.PublicTradeEvent;

/** 将公共成交提交到非持久化的行情和 K 线路径。 */
public interface PublicTradePublisher {

    PublicTradePublisher NOOP = event -> { };

    void offer(PublicTradeEvent event);
}
