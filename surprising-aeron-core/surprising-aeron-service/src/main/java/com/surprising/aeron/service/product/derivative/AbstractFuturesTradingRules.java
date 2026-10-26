package com.surprising.aeron.service.product.derivative;

import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.product.OrderAdmissionMath;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Objects;

/**
 * 期货产品线（交割与永续）公共交易规则抽象基类。
 * 封装统一的合约类型、平仓盈亏计算以及开仓预留保证金和费用公式，子类专注各自特有的生命周期结算或资金费逻辑。
 */
abstract class AbstractFuturesTradingRules implements ProductTradingRules {
    protected final ContractType contractType;

    protected AbstractFuturesTradingRules(ContractType contractType) {
        this.contractType = Objects.requireNonNull(contractType, "contractType is required");
    }

    @Override
    public ProductLine productLine() { return contractType.productLine(); }

    @Override
    public ContractType contractType() { return contractType; }

    @Override
    public long realizedPnlUnits(CoreInstrument instrument, long quantity, long entry, long execution) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, execution);
    }

    @Override
    public long reservationUnits(CoreInstrument instrument, PositionRuntime position,
                                 ResolvedPlaceOrder order, long leverage, long pendingQuantitySteps) {
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

    @Override
    public long lifecycleSettlementCashDeltaUnits(CoreInstrument instrument,
                                                  long quantity, long entry, long settlement) {
        return realizedPnlUnits(instrument, quantity, entry, settlement);
    }
}
