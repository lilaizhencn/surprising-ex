package com.surprising.aeron.service.business.spot;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.account.CoreUserState;
import com.surprising.aeron.service.state.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.position.PositionRuntime;

import static com.surprising.aeron.service.business.OrderAdmissionMath.fragmentationSafeFeeDebit;
import static com.surprising.aeron.service.state.math.CoreContractMath.feeDeltaUnits;
import static com.surprising.aeron.service.state.math.CoreContractMath.spotBaseUnits;
import static com.surprising.aeron.service.state.math.CoreContractMath.spotQuoteUnits;

/** 现货订单准入需要冻结的资产数量计算。 */
public final class SpotOrderAdmission {
    /** 这里只计算所需冻结金额，不保存订单或账户，不需要创建实例。 */
    private SpotOrderAdmission() {
    }

    /**
     * 卖单冻结基础资产，买单冻结成交名义金额加逐步成交也足够的手续费额度。
     * @param instrument 当前现货币对的基础资产、报价资产和数量价格精度
     * @param position 统一接口保留的持仓参数，现货计算不读取
     * @param order 已解析的现货委托及其有效费率和冻结价格
     * @param leverage 统一接口保留的杠杆参数，现货计算不读取
     * @param pendingQuantitySteps 统一接口保留的其他在途数量，现货计算不读取
     * @return 对应预留资产的最小整数金额，不在本方法里实际冻结
     */
    public static long reservationUnits(CoreInstrument instrument, PositionRuntime position,
                                        ResolvedPlaceOrder order, long leverage,
                                        long pendingQuantitySteps) {
        if (order.side() == CoreOrderSide.SELL) return spotBaseUnits(instrument, order.quantitySteps());
        // 买单在冻结价格下所需的报价资产金额。
        long notional = spotQuoteUnits(instrument, order.reservationPriceTicks(), order.quantitySteps());
        // 按数量步预留的手续费扣款，避免订单拆成多笔成交后舍入导致额度不足。
        long feeDebit = fragmentationSafeFeeDebit(instrument, order);
        return Math.addExact(notional, feeDebit);
    }

    /**
     * 由确定性状态重放使用的现货冻结计算入口。
     *
     * <p>该方法跨越 {@code state} 包调用，因此显式公开；计算规则仍只属于现货产品线。</p>
     * @param state 统一重放接口传入的完整业务状态，现货金额计算不读取
     * @param instrument 当前现货币对的资产和精度配置
     * @param user 统一重放接口传入的用户状态，现货金额计算不读取
     * @param command 已解析的方向、数量、冻结价格和有效费率
     * @param activeOrderIndex 统一重放接口传入的活动订单索引，现货金额计算不读取
     * @return 对应预留资产的最小整数金额，不修改传入状态
     */
    public static long reservationUnitsForState(
            TradingCoreState state,
            CoreInstrument instrument,
            CoreUserState user,
            ResolvedPlaceOrder command,
            ActiveOrderIndex activeOrderIndex) {
        if (command.side() == CoreOrderSide.SELL) return spotBaseUnits(instrument, command.quantitySteps());
        // 重放路径按整笔订单的冻结价格和数量计算报价资产金额。
        long notional = spotQuoteUnits(instrument, command.reservationPriceTicks(), command.quantitySteps());
        // 费用现金变化为负才需要追加冻结；返佣不提前折算成可用额度。
        long fee = feeDeltaUnits(instrument, command.reservationPriceTicks(),
                command.quantitySteps(), command.takerFeeRatePpm());
        return Math.addExact(notional, Math.max(0, Math.negateExact(fee)));
    }
}
