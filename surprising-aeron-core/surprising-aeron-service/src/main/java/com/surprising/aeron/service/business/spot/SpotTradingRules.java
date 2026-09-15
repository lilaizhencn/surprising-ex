package com.surprising.aeron.service.business.spot;

import com.surprising.aeron.service.state.math.*;

import com.surprising.aeron.service.business.ProductTradingRules;
import com.surprising.aeron.service.state.math.CoreContractMath;

import com.surprising.aeron.service.state.SpotOrderAdmission;

import com.surprising.aeron.service.state.CoreInstrumentState;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.RuntimeOrderAdmission;
import com.surprising.aeron.service.state.CoreStateRejectedException;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

public final class SpotTradingRules implements ProductTradingRules {
    public ProductLine productLine() { return ProductLine.SPOT; }
    public ContractType contractType() { return ContractType.SPOT; }
    @Override
    public long reservationUnits(CoreInstrumentState instrument, PositionRuntime position,
                                 ResolvedPlaceOrder order, long leverage,
                                 RuntimeOrderAdmission.AdmissionSummary admissionSummary) {
        return SpotOrderAdmission.reservationUnits(
                instrument, position, order, leverage, admissionSummary);
    }
}
