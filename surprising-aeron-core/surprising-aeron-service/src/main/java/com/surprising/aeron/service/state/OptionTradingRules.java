package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

final class OptionTradingRules implements ProductTradingRules {
    public ProductLine productLine() { return ProductLine.OPTION; }
    public ContractType contractType() { return ContractType.VANILLA_OPTION; }
    public long premiumDeltaUnits(CoreInstrumentState instrument, CoreOrderSide side,
                                  long priceTicks, long quantitySteps) {
        requireInstrument(instrument);
        long premium = OptionContractMath.optionPremiumUnits(instrument, priceTicks, quantitySteps);
        return side == CoreOrderSide.BUY ? Math.negateExact(premium) : premium;
    }
    public long realizedPnlUnits(CoreInstrumentState instrument, long quantity, long entry, long execution) {
        requireInstrument(instrument);
        return 0;
    }
    public long lifecycleCashDeltaUnits(CoreInstrumentState instrument, long quantity, long entry, long settlement) {
        requireInstrument(instrument);
        return Math.multiplyExact(OptionContractMath.optionSettlementCashUnits(instrument, settlement), quantity);
    }
    @Override
    public long reservationUnits(CoreInstrumentState instrument, PositionRuntime position,
                                 ResolvedPlaceOrder order, long leverage,
                                 RuntimeOrderAdmission.AdmissionSummary admissionSummary) {
        return OptionOrderAdmission.reservationUnits(
                instrument, position, order, leverage, admissionSummary);
    }
}
