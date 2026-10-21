package com.surprising.instrument.api.model;

import java.time.Instant;

public record OptionExerciseEvent(
        String instrumentId,
        long changeId,
        String underlyingInstrumentId,
        com.surprising.product.api.ProductLine underlyingProductLine,
        long strikePriceUnits,
        long underlyingSettlementPriceUnits,
        long cashSettlementUnitsPerContract,
        OptionType optionType,
        OptionExerciseStyle optionExerciseStyle,
        Instant expiryTime,
        Instant deliveryTime,
        ContractSettlementMethod settlementMethod,
        InstrumentStatus status,
        Instant eventTime,
        InstrumentResponse instrument) {

    public OptionExerciseEvent {
        com.surprising.product.api.InstrumentIds.parse(instrumentId);
        com.surprising.product.api.InstrumentIds.parse(underlyingInstrumentId);
        if (underlyingProductLine == null || underlyingProductLine == com.surprising.product.api.ProductLine.OPTION) throw new IllegalArgumentException("invalid underlying product line");
        if (instrumentId == null || instrumentId.isBlank() || changeId <= 0L || underlyingInstrumentId == null
                || underlyingInstrumentId.isBlank() || strikePriceUnits <= 0L || underlyingSettlementPriceUnits <= 0L
                || cashSettlementUnitsPerContract < 0L
                || optionType == null || optionExerciseStyle == null || status != InstrumentStatus.CLOSED
                || settlementMethod != ContractSettlementMethod.CASH || eventTime == null) {
            throw new IllegalArgumentException("期权行权事件必须携带有效合约和标的结算价");
        }
        if (instrument != null && (!instrumentId.equals(Integer.toString(instrument.instrumentId()))
                || changeId != instrument.changeId() || instrument.instrumentType() != InstrumentType.OPTION
                || !underlyingInstrumentId.equals(instrument.underlyingInstrumentId())
                || underlyingProductLine != instrument.underlyingProductLine()
                || instrument.strikePriceUnits() == null || strikePriceUnits != instrument.strikePriceUnits()
                || optionType != instrument.optionType() || optionExerciseStyle != instrument.optionExerciseStyle()
                || instrument.status() != InstrumentStatus.CLOSED)) {
            throw new IllegalArgumentException("期权行权事件中的合约快照与事件不一致");
        }
    }
}
