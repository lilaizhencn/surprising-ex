package com.surprising.trading.order.model;

import com.surprising.instrument.api.model.ContractType;
import java.util.Set;

public record InstrumentRule(
        String symbol,
        long version,
        String status,
        ContractType contractType,
        Set<String> supportedOrderTypes,
        Set<String> supportedTimeInForce,
        boolean marketOrderEnabled,
        boolean postOnlyEnabled,
        boolean reduceOnlyEnabled,
        long minQuantitySteps,
        long maxQuantitySteps,
        long minNotionalUnits,
        long maxNotionalUnits,
        long notionalMultiplierUnits,
        long maxLeveragePpm,
        long initialMarginRatePpm) {
}
