package com.surprising.aeron.service.product.option;

import com.surprising.aeron.model.CoreOrderState;
import com.surprising.aeron.model.CoreOrderStatus;
import com.surprising.aeron.model.CorePositionState;
import com.surprising.aeron.model.TradingCoreState;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.account.CoreUserState;
import com.surprising.aeron.service.core.index.ActiveOrderIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.order.state.OrderReservation;
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
     * @param leverage 统一接口传入的杠杆值，期权冻结公式不读取它
     * @param pendingQuantitySteps 同范围内需要计入风险的其他在途卖单数量，单位为数量步数
     * @return 结算资产的最小整数预留金额，不在本方法里实际冻结
     */
    public static long reservationUnits(CoreInstrument instrument, PositionRuntime position,
                                        ResolvedPlaceOrder order, long leverage,
                                        long pendingQuantitySteps) {
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
     * 根据确定性状态重放期权冻结计算；这里读取用户已保存的持仓和预留，不修改它们。
     * @param state 包含完整订单表的确定性业务状态
     * @param instrument 当前期权的精度、资产和风险档位配置
     * @param user 需要重放的用户状态，包含持仓与预留订单编号
     * @param command 已解析的期权委托及其有效价格、数量和费率
     * @param activeOrderIndex 统一重放接口提供的活动订单索引，此入口直接读取用户预留和状态订单表
     * @return 以结算资产最小整数单位表示的预留金额
     */
    public static long reservationUnitsForState(
            TradingCoreState state,
            CoreInstrument instrument,
            CoreUserState user,
            ResolvedPlaceOrder command,
            ActiveOrderIndex activeOrderIndex) {
        // 只读取当前币对和持仓方向对应的已有期权持仓。
        CorePositionState position = user.positions().get(positionKey(instrument.instrumentId(), command.positionSide()));
        // 已有持仓的带方向数量，无持仓时以 0 参与计算。
        long currentQuantity = position == null ? 0 : position.signedQuantitySteps();
        // 本单的带方向数量，买单为正、卖单为负。
        long signedOrder = command.side() == CoreOrderSide.BUY
                ? command.quantitySteps() : Math.negateExact(command.quantitySteps());

        // 已有仓位的绝对数量，作为平仓数量和保证金释放比例的上界。
        long currentAbs = Math.absExact(currentQuantity);
        // 判断订单与原持仓是否方向相反，相反时先考虑平仓部分。
        boolean opposite = currentQuantity != 0
                && Long.signum(currentQuantity) != Long.signum(signedOrder);
        // 本单可用于关闭已有持仓的数量，不能超过任一侧的数量。
        long closeSteps = opposite ? Math.min(currentAbs, command.quantitySteps()) : 0;
        // 除去平仓后的新增数量；只减仓标记会把新增量限制为 0。
        long openSteps = command.reduceOnly() ? 0
                : Math.subtractExact(command.quantitySteps(), closeSteps);
        // 此重放路径按整单数量计算费用现金变化，正数为返佣，负数为扣款。
        long fee = CoreContractMath.feeDeltaUnits(instrument, command.reservationPriceTicks(),
                command.quantitySteps(), command.takerFeeRatePpm());
        // 只把手续费扣款加入预留，不把尚未产生的返佣当作可用资金。
        long feeDebit = Math.max(0, Math.negateExact(fee));
        if (command.side() == CoreOrderSide.BUY) {
            // 整笔买单要支付的权利金，使用当前重放订单的冻结价格计算。
            long premium = OptionContractMath.optionPremiumUnits(
                    instrument, command.reservationPriceTicks(), command.quantitySteps());
            // 买单平掉原有空仓后可按比例释放的已有持仓保证金。
            long releasedMargin = position == null || closeSteps == 0 ? 0
                    : proportional(position.positionMarginUnits(), closeSteps, currentAbs);
            return Math.max(1, Math.max(0,
                    Math.subtractExact(Math.addExact(premium, feeDebit), releasedMargin)));
        }
        if (openSteps == 0) return Math.max(1, feeDebit);
        // 累加该用户同币对、同持仓方向、非只减仓的未完成卖单剩余数量。
        long pendingSell = 0;
        for (/* 用户已有资金预留对应的订单编号，仅扫描该用户自己的预留。 */ Long orderId : user.reservations().keySet()) {
            // 从确定性订单表查出预留对应的订单，再按状态和产品范围筛选。
            CoreOrderState order = state.orders().get(orderId);
            if (order != null && order.status() == CoreOrderStatus.OPEN && !order.reduceOnly()
                    && order.instrumentId().equals(instrument.instrumentId())
                    && order.positionSide() == command.positionSide()
                    && order.side() == CoreOrderSide.SELL) {
                pendingSell = Math.addExact(pendingSell, order.remainingQuantitySteps());
            }
        }
        // 把已有卖单和本单一起计入可能形成的空仓规模。
        long totalSellOrders = Math.addExact(pendingSell, command.quantitySteps());
        // 假定卖单全部成交后的带方向数量，只用于本次风险评估。
        long projectedSigned = Math.subtractExact(currentQuantity, totalSellOrders);
        // 预计空仓的绝对数量；预计仍持有多头时不增加这部分卖方风险。
        long projectedRisk = Math.max(0, Math.negateExact(projectedSigned));
        // 按指数价格换算的预计风险名义金额，用它确定风险档位。
        long projectedNotional = CoreContractMath.riskNotionalUnits(
                instrument, projectedRisk, command.indexPriceTicks());
        // 与预计空仓规模对应的期权风险档位。
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        // 本单新增空仓所需卖方保证金，最后加上费用扣款预留。
        long margin = OptionContractMath.optionSellOpenOrderMarginUnits(instrument,
                command.reservationPriceTicks(), command.markPriceTicks(), openSteps,
                command.indexPriceTicks(), command.forwardPriceTicks(), bracket);
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

    /**
     * 生成期权持仓查找键，双向持仓附加方向，净持仓使用标准币对编号。
     * @param instrumentId 当前期权的币对编号
     * @param side 要查找的净持仓或多空方向
     */
    private static String positionKey(String instrumentId, CorePositionSide side) {
        // 先统一币对编号写法，避免同一币对在持仓表中出现不同形式的键。
        String normalized = OrderReservation.requireInstrumentId(instrumentId);
        return side.hedgeSide() ? normalized + ':' + side.name() : normalized;
    }
}
