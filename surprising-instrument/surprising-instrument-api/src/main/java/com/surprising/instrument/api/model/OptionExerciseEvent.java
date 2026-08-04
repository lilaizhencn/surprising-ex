package com.surprising.instrument.api.model;

import java.time.Instant;

public record OptionExerciseEvent(
        String symbol,
        long version,
        String underlyingSymbol,
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
        if (symbol == null || symbol.isBlank() || version <= 0L || underlyingSymbol == null
                || underlyingSymbol.isBlank() || strikePriceUnits <= 0L || underlyingSettlementPriceUnits <= 0L
                || cashSettlementUnitsPerContract < 0L
                || optionType == null || optionExerciseStyle == null || status != InstrumentStatus.CLOSED
                || settlementMethod != ContractSettlementMethod.CASH || eventTime == null) {
            throw new IllegalArgumentException("期权行权事件必须携带有效合约和标的结算价");
        }
        if (instrument != null && (!symbol.equalsIgnoreCase(instrument.symbol())
                || version != instrument.version() || instrument.instrumentType() != InstrumentType.OPTION
                || !underlyingSymbol.equalsIgnoreCase(instrument.underlyingSymbol())
                || instrument.strikePriceUnits() == null || strikePriceUnits != instrument.strikePriceUnits()
                || optionType != instrument.optionType() || optionExerciseStyle != instrument.optionExerciseStyle()
                || instrument.status() != InstrumentStatus.CLOSED)) {
            throw new IllegalArgumentException("期权行权事件中的合约快照与事件不一致");
        }
    }
}
