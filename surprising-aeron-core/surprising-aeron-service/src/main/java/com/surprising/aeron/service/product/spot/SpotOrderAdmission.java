package com.surprising.aeron.service.product.spot;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import static com.surprising.aeron.service.product.OrderAdmissionMath.fragmentationSafeFeeDebit;
import static com.surprising.aeron.support.math.CoreContractMath.feeDeltaUnits;
import static com.surprising.aeron.support.math.CoreContractMath.spotBaseUnits;
import static com.surprising.aeron.support.math.CoreContractMath.spotQuoteUnits;

/**  现货订单准入需要冻结的资产数量计算。 构造约定： 这里只计算所需冻结金额，不保存订单或账户，不需要创建实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SpotOrderAdmission {

    /**
     * 卖单冻结基础资产，买单冻结成交名义金额加逐步成交也足够的手续费额度。
     * @param instrument 当前现货币对的基础资产、报价资产和数量价格精度
     * @param order 已解析的现货委托及其有效费率和冻结价格
     * @return 对应预留资产的最小整数金额，不在本方法里实际冻结
     */
    public static long reservationUnits(CoreInstrument instrument, ResolvedPlaceOrder order) {
        if (order.side() == CoreOrderSide.SELL) return spotBaseUnits(instrument, order.quantitySteps());
        // 买单在冻结价格下所需的报价资产金额。
        long notional = spotQuoteUnits(instrument, order.reservationPriceTicks(), order.quantitySteps());
        // 按数量步预留的手续费扣款，避免订单拆成多笔成交后舍入导致额度不足。
        long feeDebit = fragmentationSafeFeeDebit(instrument, order);
        return Math.addExact(notional, feeDebit);
    }

}
