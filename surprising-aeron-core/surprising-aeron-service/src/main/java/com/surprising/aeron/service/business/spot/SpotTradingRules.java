package com.surprising.aeron.service.business.spot;

import com.surprising.aeron.service.business.ProductTradingRules;

import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

public final class SpotTradingRules implements ProductTradingRules {
    public ProductLine productLine() { return ProductLine.SPOT; }
    public ContractType contractType() { return ContractType.SPOT; }
    @Override
    public long reservationUnits(CoreInstrument instrument, PositionRuntime position,
                                 ResolvedPlaceOrder order, long leverage,
                                 long pendingQuantitySteps) {
        return SpotOrderAdmission.reservationUnits(
                instrument, position, order, leverage, pendingQuantitySteps);
    }
}
