package com.surprising.instrument.api.model;

import java.time.Instant;
import com.surprising.product.api.ProductLine;

public record InstrumentEvent(
        String symbol,
        long version,
        InstrumentStatus status,
        InstrumentEventType eventType,
        Instant eventTime,
        InstrumentResponse snapshot,
        ProductLine productLine,
        long sequence) {

    /**
     * 兼容旧生产者构造方式，产品线和序列从完整快照与版本推导。
     */
    public InstrumentEvent(String symbol,
                            long version,
                            InstrumentStatus status,
                            InstrumentEventType eventType,
                            Instant eventTime,
                            InstrumentResponse snapshot) {
        this(symbol, version, status, eventType, eventTime, snapshot,
                snapshot == null || snapshot.contractType() == null
                        ? null : snapshot.contractType().productLine(), version);
    }

    public ProductLine resolvedProductLine() {
        if (productLine != null) {
            return productLine;
        }
        return snapshot == null || snapshot.contractType() == null
                ? null : snapshot.contractType().productLine();
    }

    public long resolvedSequence() {
        return sequence > 0L ? sequence : version;
    }
}
