package com.surprising.aeron.service.product.option;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

/** 期权规则入口，分别处理买卖权利金、卖方保证金以及到期的行权现金收支。 */
public final class OptionTradingRules implements ProductTradingRules {
    /** 返回期权产品线，让期权账户与其他交易产品的账户保持隔离。 */
    public ProductLine productLine() { return ProductLine.OPTION; }
    /** 返回普通期权合约类型；具体看涨、看跌和合约参数仍来自币对配置。 */
    public ContractType contractType() { return ContractType.VANILLA_OPTION; }
    /**
     * 此分支不增加额外到期门槛；公共维护、产品和价格校验由统一生命周期入口负责。
     * @param instrument 正在结算的期权合约配置
     * @param command 本次到期或维护结算命令
     */
    @Override
    public void validateLifecycleSettlementProductRule(CoreInstrument instrument,
                                                        SettleInstrumentCommand command) {
        // 期权到期结算由公共维护校验负责准入，这里不重复增加检查。
    }
    /**
     * 计算权利金对账户的现金影响，买入扣款、卖出入账。
     * @param instrument 当前期权的数量、价格和结算资产精度
     * @param side 本次成交的买卖方向
     * @param priceTicks 权利金成交价格，单位为价格 tick
     * @param quantitySteps 成交数量，单位为数量步数
     * @return 结算资产最小整数单位表示的现金变化，买入为负、卖出为正
     */
    public long premiumDeltaUnits(CoreInstrument instrument, CoreOrderSide side,
                                  long priceTicks, long quantitySteps) {
        requireInstrument(instrument);
        // 先算权利金金额的绝对值，再根据买卖方向决定是扣款还是入账。
        long premium = OptionContractMath.optionPremiumUnits(instrument, priceTicks, quantitySteps);
        return side == CoreOrderSide.BUY ? Math.negateExact(premium) : premium;
    }
    /**
     * 期权不在这个期货价差入口重复记盈亏；核对产品后固定返回 0，现金收支走权利金和到期结算。
     * @param instrument 必须属于期权产品的合约配置
     * @param quantity 统一接口传入的带方向数量，此实现不读取
     * @param entry 统一接口传入的入场价格，此实现不读取
     * @param execution 统一接口传入的成交价格，此实现不读取
     */
    public long realizedPnlUnits(CoreInstrument instrument, long quantity, long entry, long execution) {
        requireInstrument(instrument);
        return 0;
    }
    /**
     * 将每单位期权的到期现金额乘以带方向的持仓数量，得到本持仓的行权或失效现金结果。
     * @param instrument 当前期权合约配置，包含行权价、方向和结算精度
     * @param quantity 带方向的待结算持仓数量，单位为数量步数
     * @param entry 为统一接口保留的入场价格，此计算不读取
     * @param settlement 本次到期结算价格，单位为价格 tick
     * @return 结算资产最小整数单位表示的现金变化，失效无行权价值时为 0
     */
    public long lifecycleCashDeltaUnits(CoreInstrument instrument, long quantity, long entry, long settlement) {
        requireInstrument(instrument);
        return Math.multiplyExact(OptionContractMath.optionSettlementCashUnits(instrument, settlement), quantity);
    }
    /**
     * 给统一生命周期结算入口提供同一套期权现金计算，不在此处直接写账户。
     * @param instrument 当前期权合约配置
     * @param quantity 带方向的待结算数量，单位为数量步数
     * @param entry 统一接口保留的入场价格，期权到期现金计算不读取
     * @param settlement 到期或维护结算价格，单位为价格 tick
     */
    @Override
    public long lifecycleSettlementCashDeltaUnits(CoreInstrument instrument,
                                                  long quantity, long entry, long settlement) {
        return lifecycleCashDeltaUnits(instrument, quantity, entry, settlement);
    }
    /**
     * 按期权专有公式计算冻结，区分买方权利金、卖方开仓保证金以及已有仓位可释放的保证金。
     * @param instrument 当前期权的资产、精度和风险档位配置
     * @param position 当前对应持仓，没有持仓时为 null
     * @param order 已解析的订单方向、价格、数量和有效费率
     * @param leverage 统一接口传入的杠杆值，期权冻结公式不读取此参数
     * @param pendingQuantitySteps 需要与新卖单合并评估的其他在途卖单数量，单位为数量步数
     * @return 需要预留的结算资产最小整数金额
     */
    @Override
    public long reservationUnits(CoreInstrument instrument, PositionRuntime position,
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
        long feeDebit = CoreContractMath.fragmentationSafeFeeDebit(instrument, order);
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
     */
    private static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }
}
