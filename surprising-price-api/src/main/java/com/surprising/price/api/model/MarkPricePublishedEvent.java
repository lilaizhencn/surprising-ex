package com.surprising.price.api.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Single mark-price publication containing both the compact business result and its calculation inputs.
 *
 * <p>Real-time consumers use {@link #result()}; the audit consumer persists the complete envelope
 * asynchronously from the same Kafka record.</p>
 */
public record MarkPricePublishedEvent(
        MarkPriceEvent result,
        IndexPriceEvent indexInput,
        PerpBookTickerEvent bookInput,
        PerpTradeEvent tradeInput,
        PerpFundingRateEvent fundingInput,
        BigDecimal basisAverage,
        long basisWindowSeconds,
        Instant calculatedAt) {
}
