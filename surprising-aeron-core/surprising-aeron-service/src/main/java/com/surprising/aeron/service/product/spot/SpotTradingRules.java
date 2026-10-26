package com.surprising.aeron.service.product.spot;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

/** 现货规则入口：买单预留报价资产，卖单预留基础资产，不使用合约持仓的保证金口径。 */
public final class SpotTradingRules implements ProductTradingRules {
    /** 返回现货产品线，供命令和状态隔离检查使用。 */
    public ProductLine productLine() { return ProductLine.SPOT; }
    /** 返回本规则接受的现货合约类型，不允许借此处理永续、交割或期权。 */
    public ContractType contractType() { return ContractType.SPOT; }
    /**
     * 计算现货订单需要预留的资产，买单同时覆盖手续费扣款所需额度。
     * @param instrument 当前现货币对的精度和资产配置
     * @param position 统一接口传入的持仓参数；现货冻结计算不读取它
     * @param order 已解析的买卖方向、数量、冻结价格和有效费率
     * @param leverage 统一接口传入的杠杆参数；现货冻结计算不读取它
     * @param pendingQuantitySteps 统一接口传入的其他在途数量；现货冻结计算不读取它
     * @return 冻结资产的最小整数单位，买入时为报价资产，卖出时为基础资产
     */
    @Override
    public long reservationUnits(CoreInstrument instrument, PositionRuntime position,
                                 ResolvedPlaceOrder order, long leverage,
                                 long pendingQuantitySteps) {
        if (order.side() == CoreOrderSide.SELL) return CoreContractMath.spotBaseUnits(instrument, order.quantitySteps());
        long notional = CoreContractMath.spotQuoteUnits(instrument, order.reservationPriceTicks(), order.quantitySteps());
        long feeDebit = CoreContractMath.fragmentationSafeFeeDebit(instrument, order);
        return Math.addExact(notional, feeDebit);
    }
}
