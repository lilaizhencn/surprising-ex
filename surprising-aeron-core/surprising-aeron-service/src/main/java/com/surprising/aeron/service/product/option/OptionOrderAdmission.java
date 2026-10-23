package com.surprising.aeron.service.product.option;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.product.OrderAdmissionMath;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**  期权订单准入的权利金、卖方保证金和已有持仓风险计算。 构造约定： 仅提供期权冻结计算，不创建独立的账户或订单状态。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OptionOrderAdmission {

    /**
     * 在线计算期权订单需要预留的资金，买方覆盖权利金，卖方覆盖新开空仓的风险保证金。
     * @param instrument 当前期权合约的价格、数量、结算资产和风险档位配置
     * @param position 对应已有持仓，没有持仓时为 null
     * @param order 已解析的订单，带有冻结价、标记价、指数价、远期价和有效费率
     * @param pendingQuantitySteps 同范围内需要计入风险的其他在途卖单数量，单位为数量步数
     * @return 结算资产的最小整数预留金额，不在本方法里实际冻结
     */
    public static long reservationUnits(CoreInstrument instrument, PositionRuntime position, ResolvedPlaceOrder order, long pendingQuantitySteps) {
        // 已有期权持仓，正数为多头、负数为空头，没有持仓时为 0。
        long current = position == null ? 0 : position.signedQuantitySteps();
        // 买单数量记正，卖单数量记负，用来区分本次是在平仓还是继续开仓。
        long signedOrder = order.side() == CoreOrderSide.BUY
                ? order.quantitySteps() : Math.negateExact(order.quantitySteps());

        // 已有仓位的数量绝对值，后面用来限制本单最多能平掉多少仓位。
        long currentAbs = Math.absExact(current);
        // 只有本单方向与已有持仓相反时，才先处理平仓部分。
        boolean opposite = current != 0 && Long.signum(current) != Long.signum(signedOrder);
        // 本单能够关闭的已有仓位数量，不超过已有仓位或订单数量。
        long closeSteps = opposite ? Math.min(currentAbs, order.quantitySteps()) : 0;
        // 扣除平仓部分后的新开仓数量，只减仓单始终不计新开仓。
        long openSteps = order.reduceOnly() ? 0 : Math.subtractExact(order.quantitySteps(), closeSteps);
        // 按数量步覆盖手续费扣款，避免多次成交的舍入导致预留不足。
        long feeDebit = OrderAdmissionMath.fragmentationSafeFeeDebit(instrument, order);
        if (order.side() == CoreOrderSide.BUY) {
            // 买入整笔委托需要支付的权利金，不因为其中一部分用于平仓而省略。
            long premium = OptionContractMath.optionPremiumUnits(
                    instrument, order.reservationPriceTicks(), order.quantitySteps());
            // 买入平掉已有空仓时，可按平仓比例释放原来占用的持仓保证金。
            long releasedMargin = position == null || closeSteps == 0 ? 0
                    : proportional(position.positionMarginUnits(), closeSteps, currentAbs);
            return Math.max(1, Math.max(0,
                    Math.subtractExact(Math.addExact(premium, feeDebit), releasedMargin)));
        }
        if (openSteps == 0) return Math.max(1, feeDebit);
        // 合并其他在途卖单和本单，防止分别检查后低估最终可能形成的空仓。
        long totalSellOrders = Math.addExact(pendingQuantitySteps, order.quantitySteps());
        // 假设这些卖单全部执行后的带方向持仓，只用于风险选档，不写入账户。
        long projectedSigned = Math.subtractExact(current, totalSellOrders);
        // 风险保证金只针对预计空仓数量；预计仍为多头时取 0。
        long projectedRisk = Math.max(0, Math.negateExact(projectedSigned));
        // 用指数价格把预计空仓转换为风险名义金额，决定要使用哪个档位。
        long projectedNotional = CoreContractMath.riskNotionalUnits(
                instrument, projectedRisk, order.indexPriceTicks());
        // 该预计风险规模对应的期权保证金档位。
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        // 本单新开空仓所需的卖方保证金，再与手续费预留相加。
        long margin = OptionContractMath.optionSellOpenOrderMarginUnits(instrument,
                order.reservationPriceTicks(), order.markPriceTicks(), openSteps,
                order.indexPriceTicks(), order.forwardPriceTicks(), bracket);
        return Math.max(1, Math.addExact(margin, feeDebit));
    }

    /**
     * 按平仓占比取出可释放金额；全部平仓直接返回原金额，部分平仓使用整数除法。
     * @param units 当前持仓已经占用的保证金，单位为结算资产最小整数单位
     * @param part 本次平掉的数量，单位为数量步数
     * @param total 原持仓的绝对数量，单位为数量步数
     */
    private static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }

}
