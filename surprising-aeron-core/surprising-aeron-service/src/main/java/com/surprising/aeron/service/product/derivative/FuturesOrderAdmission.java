package com.surprising.aeron.service.product.derivative;

import com.surprising.aeron.model.CoreLeverageKey;
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

/** 四条期货产品线的下单预留计算：先按本合约风险档位计算开仓保证金，再加上足够覆盖成交分片的费用。 这里只读入持仓和配置并返回金额，不负责真正冻结资产，也不把线性与反向合约的数学公式混用。 构造约定： 仅提供无状态的金额计算，不需要构造计算器实例。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class FuturesOrderAdmission {

    /**
     * 计算在线下单要预留的保证金与费用；只减仓单不开新仓，因此保证金部分为 0。
     * @param instrument 当前永续或交割合约的精度、资产与风险档位配置
     * @param position 当前对应持仓，没有持仓时为 null
     * @param order 已解析的委托方向、数量、价格和有效费率
     * @param leverage 杠杆倍数，按一百万倍缩放保存
     * @param pendingQuantitySteps 统一接口传入的其他在途数量，本期货公式没有读取它
     * @return 结算资产最小整数单位表示的预留金额，当前结果至少为 1
     */
    public static long reservationUnits(CoreInstrument instrument, PositionRuntime position,
                                        ResolvedPlaceOrder order, long leverage,
                                        long pendingQuantitySteps) {
        // 现有持仓的带方向数量；没有持仓时以 0 参与风险规模计算。
        long current = position == null ? 0 : position.signedQuantitySteps();
        // 买入记为正、卖出记为负，只用于计算方向，不在这里改变持仓。
        long signedOrder = order.side() == CoreOrderSide.BUY
                ? order.quantitySteps() : Math.negateExact(order.quantitySteps());

        // 只减仓不预留开仓保证金；其他订单按整个委托数量计算开仓部分。
        long openSteps = order.reduceOnly() ? 0 : order.quantitySteps();
        // 用已有持仓绝对量加新单数量选风险档位，不把这个估算值当作真实成交后持仓。
        long projectedRisk = Math.addExact(Math.absExact(current), order.quantitySteps());
        // 为风险计算附上本次委托方向，保留各产品数学函数需要的有符号输入。
        long projectedSigned = signedOrder > 0 ? projectedRisk : Math.negateExact(projectedRisk);
        // 按合约风险档位、指定杠杆和冻结价格得到开仓保证金。
        long margin = openingMargin(instrument, projectedSigned, signedOrder, openSteps,
                order.reservationPriceTicks(), leverage, order.indexPriceTicks(), order.forwardPriceTicks());
        // 覆盖订单拆成多次成交时的费用舍入，返佣不抵减这项冻结。
        long feeDebit = OrderAdmissionMath.fragmentationSafeFeeDebit(instrument, order);
        return Math.max(1, Math.addExact(margin, feeDebit));
    }

    /**
     * 选择风险档位，并从币对、档位和杠杆三个要求中取最严格的开仓保证金率。
     * @param instrument 当前合约配置，决定线性或反向计算口径
     * @param projectedQuantity 供风险选档使用的预计带方向数量，单位为数量步数
     * @param signedFill 本次委托的带方向数量，用符号选择买卖方向
     * @param openSteps 需要按开仓处理的数量，0 表示没有开仓保证金
     * @param priceTicks 订单冻结价格，单位为价格 tick
     * @param leveragePpm 杠杆倍数，按一百万倍缩放保存
     * @param indexPriceTicks 计算接口传入的指数价格，单位为价格 tick
     * @param forwardPriceTicks 计算接口传入的远期价格，单位为价格 tick
     */
    private static long openingMargin(
            CoreInstrument instrument, long projectedQuantity, long signedFill, long openSteps,
            long priceTicks, long leveragePpm, long indexPriceTicks, long forwardPriceTicks) {
        if (openSteps == 0) return 0;
        // 将预计数量转换成当前产品口径的风险名义金额，作为选择档位的依据。
        long projectedNotional = CoreContractMath.riskNotionalUnits(instrument,
                Math.absExact(projectedQuantity), priceTicks);
        // 取该风险规模对应的档位，不在这里改动用户的风险或持仓状态。
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        // 实际采用的保证金率，以百万分之一为单位，不能比任一配置要求更低。
        long rate = Math.max(Math.max(instrument.initialMarginRatePpm(), bracket.initialMarginRatePpm()),
                CoreContractMath.initialMarginRateFromLeverage(leveragePpm));
        return CoreContractMath.openingMarginUnits(instrument,
                signedFill > 0 ? CoreOrderSide.BUY : CoreOrderSide.SELL, priceTicks, openSteps, rate,
                indexPriceTicks, forwardPriceTicks, bracket.optionMarginFactorPpm());
    }

    /**
     * 从已保存的确定性状态计算预留，供状态重放使用；不在重放时修改账户。
     * @param state 包含用户杠杆设置等信息的确定性业务状态
     * @param instrument 本次订单的合约配置
     * @param user 订单所属用户的持仓与账户状态
     * @param command 已解析的订单方向、价格、数量、保证金模式和费率
     * @param activeOrderIndex 统一重放接口提供的活动订单索引，本公式没有读取它
     * @return 以结算资产最小整数单位表示的预留金额，当前结果至少为 1
     */
    public static long reservationUnitsForState(
            TradingCoreState state,
            CoreInstrument instrument,
            CoreUserState user,
            ResolvedPlaceOrder command,
        ActiveOrderIndex activeOrderIndex) {
        // 用币对和持仓方向找到该订单对应的持仓，不能拿另一个方向的持仓抵扣。
        CorePositionState position = user.positions().get(
                positionKey(instrument.instrumentId(), command.positionSide()));
        // 没有已有持仓时用 0，已有持仓则保留它的方向。
        long currentQuantity = position == null ? 0 : position.signedQuantitySteps();
        // 本次委托的带方向数量，买入为正、卖出为负。
        long signedOrder = command.side() == CoreOrderSide.BUY
                ? command.quantitySteps() : Math.negateExact(command.quantitySteps());

        // 只减仓单不增加开仓保证金，其他委托按完整下单量预留。
        long openSteps = command.reduceOnly() ? 0 : command.quantitySteps();
        // 优先用该用户、币对、保证金模式的杠杆设置，缺省时用币对允许的最大杠杆。
        long leverage = state.leverages().getOrDefault(
                new CoreLeverageKey(user.userId(), instrument.instrumentId(), command.marginMode()),
                instrument.maxLeveragePpm());
        // 为风险选档合并已有持仓绝对量和新单数量，不把它写回真实仓位。
        long projectedRiskQuantity = Math.addExact(Math.absExact(currentQuantity), command.quantitySteps());
        // 给预计风险数量附上本次委托方向，交给合约数学函数处理。
        long projectedSteps = signedOrder > 0 ? projectedRiskQuantity : Math.negateExact(projectedRiskQuantity);
        // 与在线入口共用的开仓保证金计算结果。
        long margin = openingMargin(instrument, projectedSteps, signedOrder, openSteps,
                command.reservationPriceTicks(), leverage, command.indexPriceTicks(),
                command.forwardPriceTicks());
        // 这条重放公式保留买方期权的权利金项；普通永续和交割合约的这一项为 0。
        long premium = instrument.contractType().isOption() && command.side() == CoreOrderSide.BUY
                ? OptionContractMath.optionPremiumUnits(instrument, command.reservationPriceTicks(),
                command.quantitySteps()) : 0;
        // 此重放入口按整笔数量计算费用变化，只有扣款部分会加入预留。
        long fee = CoreContractMath.feeDeltaUnits(instrument, command.reservationPriceTicks(),
                command.quantitySteps(), command.takerFeeRatePpm());
        return Math.max(1, Math.addExact(Math.addExact(margin, premium), Math.max(0, Math.negateExact(fee))));
    }

    /**
     * 生成用户持仓表使用的键；双向持仓附加方向，净持仓直接使用标准币对编号。
     * @param instrumentId 订单对应的币对编号
     * @param side 净持仓或多空方向标记
     */
    private static String positionKey(String instrumentId, CorePositionSide side) {
        // 先检查并规范化币对编号，避免同一币对出现多种键写法。
        String normalized = OrderReservation.requireInstrumentId(instrumentId);
        return side.hedgeSide() ? normalized + ':' + side.name() : normalized;
    }
}
