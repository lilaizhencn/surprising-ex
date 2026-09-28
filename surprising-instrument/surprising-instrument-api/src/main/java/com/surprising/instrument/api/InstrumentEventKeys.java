package com.surprising.instrument.api;

import com.surprising.instrument.api.model.InstrumentEvent;
import com.surprising.product.api.ProductLine;

/**
 * Instrument Kafka 事件的统一 key 规则。
 */
public final class InstrumentEventKeys {

    private InstrumentEventKeys() {
    }

    public static String key(ProductLine productLine, int instrumentId) {
        if (productLine == null || instrumentId <= 0) {
            throw new IllegalArgumentException("productLine and instrumentId are required");
        }
        return productLine.name() + ":" + instrumentId;
    }

    public static String key(InstrumentEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event is required");
        }
        return key(event.productLine(), event.instrumentId());
    }

    public static boolean matches(String key, InstrumentEvent event) {
        if (event == null || key == null) {
            return false;
        }
        return key.equals(key(event));
    }
}
