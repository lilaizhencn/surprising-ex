package com.surprising.price.mark.model;

import com.surprising.price.api.model.MarkPriceAuditEvent;

/** Audit envelope paired with its original Kafka JSON for lossless diagnostics. */
public record MarkPriceAuditRecord(MarkPriceAuditEvent event, String payloadJson) {
}
