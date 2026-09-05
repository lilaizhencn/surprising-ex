package com.surprising.aeron.service.state;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

final class InversePerpetualTradingRules implements ProductTradingRules {
    public ProductLine productLine() { return ProductLine.INVERSE_PERPETUAL; }
    public ContractType contractType() { return ContractType.INVERSE_PERPETUAL; }
    public long realizedPnlUnits(CoreInstrumentState instrument, long quantity, long entry, long execution) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, execution);
    }
    public long fundingDeltaUnits(CoreInstrumentState instrument, long quantity, long mark, long rate) {
        requireInstrument(instrument);
        return CoreContractMath.fundingDeltaUnits(instrument, quantity, mark, rate);
    }
    @Override
    public long reservationUnits(CoreInstrumentState instrument, PositionRuntime position,
                                 ResolvedPlaceOrder order, long leverage,
                                 RuntimeOrderAdmission.AdmissionSummary admissionSummary) {
        return FuturesOrderAdmission.reservationUnits(
                instrument, position, order, leverage, admissionSummary);
    }
}
