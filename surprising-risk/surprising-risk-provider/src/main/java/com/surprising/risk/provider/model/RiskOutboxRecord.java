package com.surprising.risk.provider.model;

public record RiskOutboxRecord(
        long id,
        String topic,
        String eventKey,
        String payload) {
}
