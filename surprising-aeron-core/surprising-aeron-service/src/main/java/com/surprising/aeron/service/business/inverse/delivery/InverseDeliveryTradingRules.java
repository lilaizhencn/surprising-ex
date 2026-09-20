package com.surprising.aeron.service.business.inverse.delivery;

import com.surprising.aeron.service.business.ProductTradingRules;
import com.surprising.aeron.service.state.math.CoreContractMath;

import com.surprising.aeron.service.business.derivative.FuturesOrderAdmission;

import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.protocol.SettleInstrumentCommand;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

public final class InverseDeliveryTradingRules implements ProductTradingRules {
    public ProductLine productLine() { return ProductLine.INVERSE_DELIVERY; }
    public ContractType contractType() { return ContractType.INVERSE_DELIVERY; }
    @Override
    public void validateLifecycleSettlementProductRule(CoreInstrument instrument,
                                                        SettleInstrumentCommand command) {
        // Delivery settlement is admitted by the shared maintenance validation.
    }
    public long realizedPnlUnits(CoreInstrument instrument, long quantity, long entry, long execution) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, execution);
    }
    public long lifecycleCashDeltaUnits(CoreInstrument instrument, long quantity, long entry, long settlement) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, settlement);
    }
    @Override
    public long lifecycleSettlementCashDeltaUnits(CoreInstrument instrument,
                                                  long quantity, long entry, long settlement) {
        return lifecycleCashDeltaUnits(instrument, quantity, entry, settlement);
    }
    @Override
    public long reservationUnits(CoreInstrument instrument, PositionRuntime position,
                                 ResolvedPlaceOrder order, long leverage,
                                 long pendingQuantitySteps) {
        return FuturesOrderAdmission.reservationUnits(
                instrument, position, order, leverage, pendingQuantitySteps);
    }
}
