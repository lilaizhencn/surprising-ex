package com.surprising.aeron.service.product;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

public final class OptionTradingRules implements ProductTradingRules {
    /** 返回期权产品线，让期权账户与其他交易产品的账户保持隔离。 */
    public ProductLine productLine() { return ProductLine.OPTION; }
    /** 返回普通期权合约类型。 */
    public ContractType contractType() { return ContractType.VANILLA_OPTION; }
    /** 此分支不增加额外到期门槛。 */
    @Override
    public void validateLifecycleSettlementProductRule(CoreInstrument instrument,
                                                        SettleInstrumentCommand command) {
    }
    /** 计算权利金对账户的现金影响，买入扣款、卖出入账。 */
    public long premiumDeltaUnits(CoreInstrument instrument, CoreOrderSide side,
                                  long priceTicks, long quantitySteps) {
        requireInstrument(instrument);
        long premium = OptionContractMath.optionPremiumUnits(instrument, priceTicks, quantitySteps);
        return side == CoreOrderSide.BUY ? Math.negateExact(premium) : premium;
    }
    /** 期权不在这个期货价差入口重复记盈亏。 */
    public long realizedPnlUnits(CoreInstrument instrument, long quantity, long entry, long execution) {
        requireInstrument(instrument);
        return 0;
    }
    /** 将每单位期权的到期现金额乘以带方向的持仓数量，得到本持仓的行权或失效现金结果。 */
    @Override
    public long lifecycleSettlementCashDeltaUnits(CoreInstrument instrument, long quantity, long entry, long settlement) {
        requireInstrument(instrument);
        return Math.multiplyExact(OptionContractMath.optionSettlementCashUnits(instrument, settlement), quantity);
    }
    /** 按期权专有公式计算冻结，区分买方权利金、卖方开仓保证金以及已有仓位可释放的保证金。 */
    @Override
    public long reservationUnits(CoreInstrument instrument, Position position,
                                 OrderCommand.ResolvedPlaceOrder order, long leverage,
                                 long pendingQuantitySteps) {
        long current = position == null ? 0 : position.signedQuantitySteps();
        long signedOrder = order.side() == CoreOrderSide.BUY
                ? order.quantitySteps() : Math.negateExact(order.quantitySteps());

        long currentAbs = Math.absExact(current);
        boolean opposite = current != 0 && Long.signum(current) != Long.signum(signedOrder);
        long closeSteps = opposite ? Math.min(currentAbs, order.quantitySteps()) : 0;
        long openSteps = order.reduceOnly() ? 0 : Math.subtractExact(order.quantitySteps(), closeSteps);
        long feeDebit = CoreContractMath.fragmentationSafeFeeDebit(instrument, order);
        if (order.side() == CoreOrderSide.BUY) {
            long premium = OptionContractMath.optionPremiumUnits(
                    instrument, order.reservationPriceTicks(), order.quantitySteps());
            long releasedMargin = position == null || closeSteps == 0 ? 0
                    : proportional(position.positionMarginUnits(), closeSteps, currentAbs);
            return Math.max(1, Math.max(0,
                    Math.subtractExact(Math.addExact(premium, feeDebit), releasedMargin)));
        }
        if (openSteps == 0) return Math.max(1, feeDebit);
        long totalSellOrders = Math.addExact(pendingQuantitySteps, order.quantitySteps());
        long projectedSigned = Math.subtractExact(current, totalSellOrders);
        long projectedRisk = Math.max(0, Math.negateExact(projectedSigned));
        long projectedNotional = CoreContractMath.riskNotionalUnits(
                instrument, projectedRisk, order.indexPriceTicks());
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        long margin = OptionContractMath.optionSellOpenOrderMarginUnits(instrument,
                order.reservationPriceTicks(), order.markPriceTicks(), openSteps,
                order.indexPriceTicks(), order.forwardPriceTicks(), bracket);
        return Math.max(1, Math.addExact(margin, feeDebit));
    }

    private static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }

    public static long openingMarginForFill(CoreInstrument instrument,
                                            long projectedQuantitySteps,
                                            long signedFillSteps,
                                            long openSteps,
                                            MarkPrice riskMark) {
        if (openSteps == 0 || signedFillSteps > 0) return 0;
        long projectedNotional = CoreContractMath.riskNotionalUnits(instrument,
                Math.absExact(projectedQuantitySteps), riskMark.indexPriceTicks());
        var bracket = CoreContractMath.maintenanceRiskBracket(instrument, projectedNotional);
        return CoreContractMath.openingMarginUnits(instrument, CoreOrderSide.SELL,
                riskMark.markPriceTicks(), openSteps, bracket.initialMarginRatePpm(),
                riskMark.indexPriceTicks(), riskMark.forwardPriceTicks(), bracket.optionMarginFactorPpm());
    }

    /** 要求期权成交前已有风险行情，且指数价、同到期远期价都为正，缺失时返回期权风险价格不足的业务拒绝。 */
    public static void requireRiskMark(MarkPrice riskMark) {
        if (riskMark == null || riskMark.indexPriceTicks() <= 0 || riskMark.forwardPriceTicks() <= 0) {
            throw new CoreStateRejectedException("OPTION_RISK_PRICE_MISSING",
                    "option fill requires index and same-expiry forward prices");
        }
    }

    /** 算出卖出开仓的权利金能覆盖多少新增保证金，最多取新增保证金与开仓部分权利金中的较小值。 */
    public static long premiumMarginFunding(CoreInstrument instrument, long premiumDelta,
                                            long openSteps, long marginIncrease, long fillPriceTicks) {
        return premiumDelta > 0 && openSteps > 0
                ? Math.min(marginIncrease,
                        OptionContractMath.optionPremiumUnits(instrument, fillPriceTicks, openSteps)) : 0;
    }

}
