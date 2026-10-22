package com.surprising.aeron.service.product.derivative;

import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import com.surprising.aeron.exception.CoreStateRejectedException;

/** 永续交易规则。每个实例固定一种合约类型，线性与反向计算由合约公式分派。 */
public final class PerpetualTradingRules implements ProductTradingRules {
    private final ContractType contractType;

    public PerpetualTradingRules(ContractType contractType) {
        if (contractType != ContractType.LINEAR_PERPETUAL && contractType != ContractType.INVERSE_PERPETUAL) {
            throw new IllegalArgumentException("perpetual contract type is required");
        }
        this.contractType = contractType;
    }

    @Override
    public ProductLine productLine() { return contractType.productLine(); }

    @Override
    public ContractType contractType() { return contractType; }

    @Override
    public long realizedPnlUnits(CoreInstrument instrument, long quantity, long entry, long execution) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, execution);
    }

    @Override
    public long reservationUnits(CoreInstrument instrument, PositionRuntime position,
                                 ResolvedPlaceOrder order, long leverage, long pendingQuantitySteps) {
        return FuturesOrderAdmission.reservationUnits(instrument, position, order, leverage, pendingQuantitySteps);
    }

    /** 永续只允许经过维护门禁批准的管理结算。 */
    @Override
    public void validateLifecycleSettlementProductRule(CoreInstrument instrument, SettleInstrumentCommand command) {
        if (!instrument.administrativeSettlement(command)) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "perpetual settlement requires an approved maintenance gate");
        }
    }

    @Override
    public long fundingDeltaUnits(CoreInstrument instrument, long quantity, long mark, long rate) {
        requireInstrument(instrument);
        return CoreContractMath.fundingDeltaUnits(instrument, quantity, mark, rate);
    }

    @Override
    public long lifecycleSettlementCashDeltaUnits(CoreInstrument instrument,
                                                  long quantity, long entry, long settlement) {
        return realizedPnlUnits(instrument, quantity, entry, settlement);
    }
}
