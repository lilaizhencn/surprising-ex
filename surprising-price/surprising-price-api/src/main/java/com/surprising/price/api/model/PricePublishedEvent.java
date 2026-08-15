package com.surprising.price.api.model;

import java.time.Instant;

/**
 * Versioned union event for the product-line price stream.
 *
 * <p>The mark-price branch contains the complete calculation envelope. Its
 * {@link MarkPricePublishedEvent#indexInput()} contains every index component
 * snapshot used for the mark-price calculation, while {@link #generatedAt()}
 * identifies when the published price was generated.</p>
 */
public record PricePublishedEvent(
        int schemaVersion,
        PriceEventType eventType,
        String symbol,
        Instant generatedAt,
        IndexPriceEvent indexPrice,
        MarkPricePublishedEvent markPrice) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public PricePublishedEvent {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported price event schema version: " + schemaVersion);
        }
        if (eventType == null || symbol == null || symbol.isBlank() || generatedAt == null) {
            throw new IllegalArgumentException("price event type, symbol, and generatedAt are required");
        }
        boolean hasIndex = indexPrice != null;
        boolean hasMark = markPrice != null;
        if (hasIndex == hasMark) {
            throw new IllegalArgumentException("price event must contain exactly one payload");
        }
        if (eventType == PriceEventType.INDEX_PRICE && (!hasIndex || hasMark)) {
            throw new IllegalArgumentException("index price event payload is required");
        }
        if (eventType == PriceEventType.MARK_PRICE && (!hasMark || hasIndex)) {
            throw new IllegalArgumentException("mark price event payload is required");
        }
        if (eventType == PriceEventType.INDEX_PRICE && indexPrice.components() == null) {
            throw new IllegalArgumentException("index price components are required");
        }
        if (eventType == PriceEventType.MARK_PRICE
                && (markPrice.result() == null || markPrice.indexInput() == null
                || markPrice.indexInput().components() == null || markPrice.calculatedAt() == null)) {
            throw new IllegalArgumentException("mark price calculation inputs and generatedAt are required");
        }
        String payloadSymbol = hasIndex ? indexPrice.symbol()
                : markPrice.result() == null ? null : markPrice.result().symbol();
        if (!symbol.equals(payloadSymbol)) {
            throw new IllegalArgumentException("price event symbol must match payload symbol");
        }
    }

    public static PricePublishedEvent index(IndexPriceEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("index price event is required");
        }
        return new PricePublishedEvent(CURRENT_SCHEMA_VERSION, PriceEventType.INDEX_PRICE,
                event.symbol(), event.eventTime(), event, null);
    }

    public static PricePublishedEvent mark(MarkPricePublishedEvent event) {
        if (event == null || event.result() == null || event.indexInput() == null
                || event.indexInput().components() == null || event.calculatedAt() == null) {
            throw new IllegalArgumentException("mark price publication and result are required");
        }
        Instant generatedAt = event.calculatedAt();
        return new PricePublishedEvent(CURRENT_SCHEMA_VERSION, PriceEventType.MARK_PRICE,
                event.result().symbol(), generatedAt, null, event);
    }
}
