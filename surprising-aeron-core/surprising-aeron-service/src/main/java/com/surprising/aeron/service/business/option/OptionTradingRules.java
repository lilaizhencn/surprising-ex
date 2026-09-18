package com.surprising.aeron.service.business.option;

import com.surprising.aeron.service.business.ProductTradingRules;

import com.surprising.aeron.service.state.math.OptionContractMath;

import com.surprising.aeron.service.state.CoreInstrumentState;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

public final class OptionTradingRules implements ProductTradingRules {
    public ProductLine productLine() { return ProductLine.OPTION; }
    public ContractType contractType() { return ContractType.VANILLA_OPTION; }
    @Override
    public void validateLifecycleSettlementProductRule(CoreInstrumentState instrument,
                                                        SettleInstrumentCommand command) {
        // Option expiry settlement is admitted by the shared maintenance validation.
    }
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
    public long lifecycleSettlementCashDeltaUnits(CoreInstrumentState instrument,
                                                  long quantity, long entry, long settlement) {
        return lifecycleCashDeltaUnits(instrument, quantity, entry, settlement);
    }
    @Override
    public long reservationUnits(CoreInstrumentState instrument, PositionRuntime position,
                                 ResolvedPlaceOrder order, long leverage,
                                 long pendingQuantitySteps) {
        return OptionOrderAdmission.reservationUnits(
                instrument, position, order, leverage, pendingQuantitySteps);
    }
}
