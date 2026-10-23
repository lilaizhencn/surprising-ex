package com.surprising.aeron.service.product.derivative;

import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.product.OrderAdmissionMath;
import com.surprising.aeron.support.math.CoreContractMath;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 四条期货产品线的下单预留计算：先按本合约风险档位计算开仓保证金，再加上足够覆盖成交分片的费用。 这里只读入持仓和配置并返回金额，不负责真正冻结资产，也不把线性与反向合约的数学公式混用。 构造约定： 仅提供无状态的金额计算，不需要构造计算器实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FuturesOrderAdmission {

    /**
     * 计算在线下单要预留的保证金与费用；只减仓单不开新仓，因此保证金部分为 0。
     * @param instrument 当前永续或交割合约的精度、资产与风险档位配置
     * @param position 当前对应持仓，没有持仓时为 null
     * @param order 已解析的委托方向、数量、价格和有效费率
     * @param leverage 杠杆倍数，按一百万倍缩放保存
     * @return 结算资产最小整数单位表示的预留金额，当前结果至少为 1
     */
    public static long reservationUnits(CoreInstrument instrument, PositionRuntime position, ResolvedPlaceOrder order, long leverage) {
        // 现有持仓的带方向数量；没有持仓时以 0 参与风险规模计算。
        long current = position == null ? 0 : position.signedQuantitySteps();

        // 只减仓不预留开仓保证金；其他订单按整个委托数量计算开仓部分。
        long openSteps = order.reduceOnly() ? 0 : order.quantitySteps();
        // 用已有持仓绝对量加新单数量选风险档位，不把这个估算值当作真实成交后持仓。
        long projectedRisk = Math.addExact(Math.absExact(current), order.quantitySteps());
        // 按合约风险档位、指定杠杆和冻结价格得到开仓保证金。
        long margin = openingMargin(instrument, projectedRisk, openSteps, order.reservationPriceTicks(), leverage);
        // 覆盖订单拆成多次成交时的费用舍入，返佣不抵减这项冻结。
        long feeDebit = OrderAdmissionMath.fragmentationSafeFeeDebit(instrument, order);
        return Math.max(1, Math.addExact(margin, feeDebit));
    }

    /**
     * 选择风险档位，并从币对、档位和杠杆三个要求中取最严格的开仓保证金率。
     * @param instrument 当前合约配置，决定线性或反向计算口径
     * @param projectedQuantity 供风险选档使用的预计风险数量，单位为数量步数
     * @param openSteps 需要按开仓处理的数量，0 表示没有开仓保证金
     * @param priceTicks 订单冻结价格，单位为价格 tick
     * @param leveragePpm 杠杆倍数，按一百万倍缩放保存
     */
    private static long openingMargin(
            CoreInstrument instrument, long projectedQuantity, long openSteps, long priceTicks, long leveragePpm) {
        if (openSteps == 0) return 0;
        long projectedNotional = CoreContractMath.riskNotionalUnits(instrument,
                Math.absExact(projectedQuantity), priceTicks);
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        long rate = Math.max(Math.max(instrument.initialMarginRatePpm(), bracket.initialMarginRatePpm()),
                CoreContractMath.initialMarginRateFromLeverage(leveragePpm));
        return com.surprising.instrument.api.math.PerpetualContractMath.initialMarginUnits(
                instrument.contractType(), openSteps, priceTicks, instrument.notionalMultiplierUnits(),
                instrument.priceTickUnits(), instrument.settleScaleUnits(), rate);
    }

}
