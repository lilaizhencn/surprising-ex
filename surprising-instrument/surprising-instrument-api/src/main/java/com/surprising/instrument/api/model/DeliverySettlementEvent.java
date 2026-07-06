package com.surprising.instrument.api.model;

import java.time.Instant;

public record DeliverySettlementEvent(
        String symbol,
        long version,
        ContractType contractType,
        Instant expiryTime,
        Instant deliveryTime,
        ContractSettlementMethod settlementMethod,
        InstrumentStatus status,
        Instant eventTime,
        InstrumentResponse instrument) {
}
