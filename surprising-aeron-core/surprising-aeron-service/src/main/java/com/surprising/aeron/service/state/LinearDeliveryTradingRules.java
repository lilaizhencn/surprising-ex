package com.surprising.aeron.service.state;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

final class LinearDeliveryTradingRules implements ProductTradingRules {
    public ProductLine productLine() { return ProductLine.LINEAR_DELIVERY; }
    public ContractType contractType() { return ContractType.LINEAR_DELIVERY; }
    public long realizedPnlUnits(CoreInstrumentState instrument, long quantity, long entry, long execution) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, execution);
    }
    public long lifecycleCashDeltaUnits(CoreInstrumentState instrument, long quantity, long entry, long settlement) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, settlement);
    }
    @Override
    public long reservationUnits(CoreInstrumentState instrument, PositionRuntime position,
                                 ResolvedPlaceOrder order, long leverage,
                                 RuntimeOrderAdmission.AdmissionSummary admissionSummary) {
        return FuturesOrderAdmission.reservationUnits(
                instrument, position, order, leverage, admissionSummary);
    }
}
