package com.surprising.aeron.service.business;

import com.surprising.aeron.service.state.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.math.CoreContractMath;

/** 各产品线订单准入共用的费用冻结计算，不拥有任何运行时状态。 */
public final class OrderAdmissionMath {
    /** 只提供无状态计算，不需要创建工具对象或为每笔订单保存一份副本。 */
    private OrderAdmissionMath() {
    }

    /**
     * 按单步费用计算冻结额度，避免先计算整笔费用时的分片舍入造成少冻结。
     * @param instrument 当前订单所属币对的费用和精度配置
     * @param order 已解析并固定冻结价格、数量和有效 taker 费率的订单
     * @return 覆盖逐步成交手续费扣款的冻结金额；返佣不会抵减这项预留
     */
    public static long fragmentationSafeFeeDebit(CoreInstrument instrument,
                                                  ResolvedPlaceOrder order) {
        // 先按一个数量步计算费用现金变化，保留该步应使用的舍入规则。
        long feePerStep = CoreContractMath.feeDeltaUnits(
                instrument, order.reservationPriceTicks(), 1, order.takerFeeRatePpm());
        // 仅预留会扣款的部分；正向返佣不作为可提前使用的资金。
        long debitPerStep = Math.max(0, Math.negateExact(feePerStep));
        return Math.multiplyExact(debitPerStep, order.quantitySteps());
    }
}
