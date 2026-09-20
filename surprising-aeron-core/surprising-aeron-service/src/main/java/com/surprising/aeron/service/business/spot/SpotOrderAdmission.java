package com.surprising.aeron.service.business.spot;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.CoreUserState;
import com.surprising.aeron.service.state.PositionRuntime;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;

import static com.surprising.aeron.service.business.OrderAdmissionMath.fragmentationSafeFeeDebit;
import static com.surprising.aeron.service.state.math.CoreContractMath.feeDeltaUnits;

/** 现货订单准入需要冻结的资产数量计算。 */
public final class SpotOrderAdmission {
    private SpotOrderAdmission() {
    }

    public static long reservationUnits(CoreInstrument instrument, PositionRuntime position,
                                        ResolvedPlaceOrder order, long leverage,
                                        long pendingQuantitySteps) {
        if (order.side() == CoreOrderSide.SELL) return order.quantitySteps();
        long notional = Math.multiplyExact(order.reservationPriceTicks(), order.quantitySteps());
        long feeDebit = fragmentationSafeFeeDebit(instrument, order);
        return Math.addExact(notional, feeDebit);
    }

    /**
     * 由确定性状态重放使用的现货冻结计算入口。
     *
     * <p>该方法跨越 {@code state} 包调用，因此显式公开；计算规则仍只属于现货产品线。</p>
     */
    public static long reservationUnitsForState(
            TradingCoreState state,
            CoreInstrument instrument,
            CoreUserState user,
            ResolvedPlaceOrder command,
            ActiveOrderIndex activeOrderIndex) {
        if (command.side() == CoreOrderSide.SELL) return command.quantitySteps();
        long notional = Math.multiplyExact(command.reservationPriceTicks(), command.quantitySteps());
        long fee = feeDeltaUnits(instrument, command.reservationPriceTicks(),
                command.quantitySteps(), command.takerFeeRatePpm());
        return Math.addExact(notional, Math.max(0, Math.negateExact(fee)));
    }
}
