package com.surprising.price.mark.model;

import com.surprising.price.api.model.MarkPricePublishedEvent;

/** Audit envelope paired with its original Kafka JSON for lossless diagnostics. */
public record MarkPriceAuditRecord(MarkPricePublishedEvent event, String payloadJson) {
}
