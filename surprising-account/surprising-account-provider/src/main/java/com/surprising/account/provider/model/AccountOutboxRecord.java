package com.surprising.account.provider.model;

public record AccountOutboxRecord(
        long id,
        String topic,
        String eventKey,
        String payload) {
}
