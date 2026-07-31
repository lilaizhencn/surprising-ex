package com.surprising.instrument.api;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.product.api.ProductLine;

/**
 * Instrument Kafka 事件的统一 key 规则。
 */
public final class InstrumentEventKeys {

    private InstrumentEventKeys() {
    }

    public static String key(ProductLine productLine, String symbol) {
        if (productLine == null || symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("productLine and symbol are required");
        }
        return productLine.name() + ":" + symbol.trim().toUpperCase(java.util.Locale.ROOT);
    }

    public static String key(InstrumentEvent event) {
        ProductLine productLine = event == null ? null : event.resolvedProductLine();
        return key(productLine, event == null ? null : event.symbol());
    }

    public static boolean matches(String key, InstrumentEvent event) {
        if (event == null || key == null) {
            return false;
        }
        return key.equals(key(event)) || key.equals(event.symbol());
    }
}
