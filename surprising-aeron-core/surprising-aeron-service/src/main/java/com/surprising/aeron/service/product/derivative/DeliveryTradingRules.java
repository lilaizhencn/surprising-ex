package com.surprising.aeron.service.product.derivative;

import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.instrument.api.model.ContractType;

/** 交割交易规则。每个实例固定一种合约类型，维护及交割准入由公共规则核对。 */
public final class DeliveryTradingRules extends AbstractFuturesTradingRules {

    public DeliveryTradingRules(ContractType contractType) {
        super(contractType);
        if (contractType != ContractType.LINEAR_DELIVERY && contractType != ContractType.INVERSE_DELIVERY) {
            throw new IllegalArgumentException("delivery contract type is required");
        }
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
