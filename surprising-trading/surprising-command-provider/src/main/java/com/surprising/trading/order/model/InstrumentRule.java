package com.surprising.trading.order.model;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentType;
import java.util.Set;

public record InstrumentRule(
        String symbol,
        long version,
        String status,
        InstrumentType instrumentType,
        ContractType contractType,
        String baseAsset,
        String quoteAsset,
        String settleAsset,
        Set<String> supportedOrderTypes,
        Set<String> supportedTimeInForce,
        boolean marketOrderEnabled,
        boolean postOnlyEnabled,
        boolean reduceOnlyEnabled,
        long quantityStepUnits,
        long minQuantitySteps,
        long maxQuantitySteps,
        long minNotionalUnits,
        long maxNotionalUnits,
        long notionalMultiplierUnits,
        long maxLeveragePpm,
        long initialMarginRatePpm) {

    public InstrumentRule(String symbol,
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
        this(symbol, version, status, InstrumentType.PERPETUAL, contractType, "", "", "",
                supportedOrderTypes, supportedTimeInForce, marketOrderEnabled, postOnlyEnabled,
                reduceOnlyEnabled, 1L, minQuantitySteps, maxQuantitySteps, minNotionalUnits,
                maxNotionalUnits, notionalMultiplierUnits, maxLeveragePpm, initialMarginRatePpm);
    }

    public boolean spot() {
        return instrumentType == InstrumentType.SPOT;
    }
}
