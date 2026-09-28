package com.surprising.instrument.api.model;

import java.time.Instant;

public record DeliverySettlementEvent(
        String instrumentId,
        long changeId,
        ContractType contractType,
        long settlementPriceTicks,
        Instant expiryTime,
        Instant deliveryTime,
        ContractSettlementMethod settlementMethod,
        InstrumentStatus status,
        Instant eventTime,
        InstrumentResponse instrument) {

    public DeliverySettlementEvent {
        com.surprising.product.api.InstrumentIds.parse(instrumentId);
        if (instrumentId == null || instrumentId.isBlank() || changeId <= 0L || settlementPriceTicks <= 0L
                || contractType == null || !contractType.isDelivery() || status != InstrumentStatus.CLOSED
                || settlementMethod != ContractSettlementMethod.CASH || eventTime == null) {
            throw new IllegalArgumentException("交割事件必须携带有效合约和结算价");
        }
        if (instrument != null && (!instrumentId.equals(Integer.toString(instrument.instrumentId()))
                || changeId != instrument.changeId() || contractType != instrument.contractType()
                || instrument.status() != InstrumentStatus.CLOSED)) {
            throw new IllegalArgumentException("交割事件中的合约快照与事件不一致");
        }
    }
}
