package com.surprising.price.api.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Complete input and output envelope used to audit one mark-price calculation.
 *
 * <p>The real-time topic carries {@link MarkPriceEvent}. This larger envelope is published only to
 * the audit topic so the asynchronous audit writer can persist every calculation input in the same
 * database row without putting database I/O on the risk path.</p>
 */
public record MarkPriceAuditEvent(
        MarkPriceEvent result,
        IndexPriceEvent indexInput,
        PerpBookTickerEvent bookInput,
        PerpTradeEvent tradeInput,
        PerpFundingRateEvent fundingInput,
        BigDecimal basisAverage,
        long basisWindowSeconds,
        Instant calculatedAt) {
}
