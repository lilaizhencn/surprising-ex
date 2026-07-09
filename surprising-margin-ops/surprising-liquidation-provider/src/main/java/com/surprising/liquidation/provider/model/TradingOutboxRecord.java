package com.surprising.liquidation.provider.model;

public record TradingOutboxRecord(
        long id,
        String topic,
        String eventKey,
        String payload) {
}
