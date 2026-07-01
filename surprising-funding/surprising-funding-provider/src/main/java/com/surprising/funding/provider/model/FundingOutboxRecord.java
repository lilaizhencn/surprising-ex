package com.surprising.funding.provider.model;

public record FundingOutboxRecord(
        long id,
        String topic,
        String eventKey,
        String payload) {
}
