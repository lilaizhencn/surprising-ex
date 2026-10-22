package com.surprising.aeron.service.product.derivative;

import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

/** 交割交易规则。每个实例固定一种合约类型，维护及交割准入由公共规则核对。 */
public final class DeliveryTradingRules implements ProductTradingRules {
    private final ContractType contractType;

    public DeliveryTradingRules(ContractType contractType) {
        if (contractType != ContractType.LINEAR_DELIVERY && contractType != ContractType.INVERSE_DELIVERY) {
            throw new IllegalArgumentException("delivery contract type is required");
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

    @Override
    public void validateLifecycleSettlementProductRule(CoreInstrument instrument, SettleInstrumentCommand command) {
        // 公共入口已经核对产品、维护批准条件及结算价，无额外的交割产品门槛。
    }

    @Override
    public long lifecycleCashDeltaUnits(CoreInstrument instrument, long quantity, long entry, long settlement) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, settlement);
    }

    @Override
    public long lifecycleSettlementCashDeltaUnits(CoreInstrument instrument,
                                                  long quantity, long entry, long settlement) {
        return lifecycleCashDeltaUnits(instrument, quantity, entry, settlement);
    }
}
