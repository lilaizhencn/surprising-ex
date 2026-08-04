package com.surprising.instrument.api.model;

import java.time.Instant;

public record DeliverySettlementEvent(
        String symbol,
        long version,
        ContractType contractType,
        long settlementPriceTicks,
        Instant expiryTime,
        Instant deliveryTime,
        ContractSettlementMethod settlementMethod,
        InstrumentStatus status,
        Instant eventTime,
        InstrumentResponse instrument) {

    public DeliverySettlementEvent {
        if (symbol == null || symbol.isBlank() || version <= 0L || settlementPriceTicks <= 0L
                || contractType == null || !contractType.isDelivery() || status != InstrumentStatus.CLOSED
                || settlementMethod != ContractSettlementMethod.CASH || eventTime == null) {
            throw new IllegalArgumentException("交割事件必须携带有效合约和结算价");
        }
        if (instrument != null && (!symbol.equalsIgnoreCase(instrument.symbol())
                || version != instrument.version() || contractType != instrument.contractType()
                || instrument.status() != InstrumentStatus.CLOSED)) {
            throw new IllegalArgumentException("交割事件中的合约快照与事件不一致");
        }
    }
}
