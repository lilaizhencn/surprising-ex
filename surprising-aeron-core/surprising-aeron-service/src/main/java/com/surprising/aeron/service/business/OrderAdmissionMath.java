package com.surprising.aeron.service.business;

import com.surprising.aeron.service.state.instrument.CoreInstrumentState;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.math.CoreContractMath;

/** 各产品线订单准入共用的费用冻结计算，不拥有任何运行时状态。 */
public final class OrderAdmissionMath {
    private OrderAdmissionMath() {
    }

    /**
     * 按单步费用计算冻结额度，避免先计算整笔费用时的分片舍入造成少冻结。
     */
    public static long fragmentationSafeFeeDebit(CoreInstrumentState instrument,
                                                  ResolvedPlaceOrder order) {
        long feePerStep = CoreContractMath.feeDeltaUnits(
                instrument, order.reservationPriceTicks(), 1, order.takerFeeRatePpm());
        long debitPerStep = Math.max(0, Math.negateExact(feePerStep));
        return Math.multiplyExact(debitPerStep, order.quantitySteps());
    }
}
