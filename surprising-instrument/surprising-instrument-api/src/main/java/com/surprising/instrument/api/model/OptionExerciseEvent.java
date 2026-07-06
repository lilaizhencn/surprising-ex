package com.surprising.instrument.api.model;

import java.time.Instant;

public record OptionExerciseEvent(
        String symbol,
        long version,
        String underlyingSymbol,
        long strikePriceUnits,
        OptionType optionType,
        OptionExerciseStyle optionExerciseStyle,
        Instant expiryTime,
        Instant deliveryTime,
        ContractSettlementMethod settlementMethod,
        InstrumentStatus status,
        Instant eventTime,
        InstrumentResponse instrument) {
}
