package com.surprising.aeron.service.business.linear.perpetual;

import com.surprising.aeron.service.business.ProductTradingRules;
import com.surprising.aeron.service.state.math.CoreContractMath;

import com.surprising.aeron.service.business.derivative.FuturesOrderAdmission;

import com.surprising.aeron.service.state.instrument.CoreInstrumentState;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.SettleInstrumentCommand;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

public final class LinearPerpetualTradingRules implements ProductTradingRules {
    public ProductLine productLine() { return ProductLine.LINEAR_PERPETUAL; }
    public ContractType contractType() { return ContractType.LINEAR_PERPETUAL; }
    @Override
    public void validateLifecycleSettlementProductRule(CoreInstrumentState instrument,
                                                        SettleInstrumentCommand command) {
        if (!instrument.administrativeSettlement(command)) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "perpetual settlement requires an approved maintenance gate");
        }
    }
    public long realizedPnlUnits(CoreInstrumentState instrument, long quantity, long entry, long execution) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, execution);
    }
    public long fundingDeltaUnits(CoreInstrumentState instrument, long quantity, long mark, long rate) {
        requireInstrument(instrument);
        return CoreContractMath.fundingDeltaUnits(instrument, quantity, mark, rate);
    }
    @Override
    public long lifecycleSettlementCashDeltaUnits(CoreInstrumentState instrument,
                                                  long quantity, long entry, long settlement) {
        return realizedPnlUnits(instrument, quantity, entry, settlement);
    }
    @Override
    public long reservationUnits(CoreInstrumentState instrument, PositionRuntime position,
                                 ResolvedPlaceOrder order, long leverage,
                                 long pendingQuantitySteps) {
        return FuturesOrderAdmission.reservationUnits(
                instrument, position, order, leverage, pendingQuantitySteps);
    }
}
