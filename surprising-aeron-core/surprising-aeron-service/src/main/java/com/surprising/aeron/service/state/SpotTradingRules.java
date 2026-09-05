package com.surprising.aeron.service.state;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

final class SpotTradingRules implements ProductTradingRules {
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
