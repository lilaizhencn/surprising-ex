package com.surprising.trading.trigger.model;

public record TriggerOutboxRecord(
        long id,
        String topic,
        String eventKey,
        String payload) {
}
