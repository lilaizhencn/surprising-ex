package com.surprising.aeron.service.product;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

public interface ProductTradingRules {

    /** 计算下单所需预留，不在计算过程中实际扣款或创建订单。 */
    long reservationUnits(CoreInstrument instrument, Position position,
                          OrderCommand.ResolvedPlaceOrder order, long leverage,
                          long pendingQuantitySteps);

    /** 返回本规则唯一支持的产品线，用于隔离运行时与账户。 */
    ProductLine productLine();

    /** 返回本规则唯一支持的合约类型，用于与币对配置再次核对。 */
    ContractType contractType();

    /** 在撤销订单和派发账户结算之前，先检查产品、维护任务批准条件与结算价格。日志顺序、撤单和实际状态写入仍由运行时负责。 */
    default void validateLifecycleSettlement(CoreInstrument instrument,
                                             SettleInstrumentCommand command) {
        requireInstrument(instrument);
        if (command == null) {
            throw new IllegalArgumentException("settlement command is required");
        }
        if (instrument.maintenance().mode()
                == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                && !instrument.administrativeSettlement(command)) {
            throw new CoreStateRejectedException("INVALID_COMMAND",
                    "settlement differs from approved maintenance task");
        }
        validateLifecycleSettlementProductRule(instrument, command);
        if (command.settlementPriceTicks() <= 0) {
            throw new CoreStateRejectedException("INVALID_SETTLEMENT_PRICE",
                    "delivery price must be positive");
        }
    }

    /** 给具体产品增加自己的生命周期准入条件。 */
    default void validateLifecycleSettlementProductRule(CoreInstrument instrument,
                                                        SettleInstrumentCommand command) {
        throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                "instrument settlement is unsupported for " + productLine());
    }

    /** 同时核对合约类型和产品线，阻止把另一条产品线的配置交给当前资金公式。 */
    default void requireInstrument(CoreInstrument instrument) {
        if (instrument.contractType() != contractType()
                || instrument.contractType().productLine() != productLine()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "instrument does not belong to settlement kernel " + productLine());
        }
    }

    /** 计算权利金现金变化。 */
    default long premiumDeltaUnits(CoreInstrument instrument, CoreOrderSide side,
                                   long priceTicks, long quantitySteps) {
        requireInstrument(instrument);
        return 0;
    }

    /** 计算平仓产生的已实现盈亏。 */
    default long realizedPnlUnits(CoreInstrument instrument, long signedCloseSteps,
                                  long entryPriceTicks, long executionPriceTicks) {
        requireInstrument(instrument);
        throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                "realized PnL is unsupported for " + productLine());
    }

    /** 计算资金费对账户的现金影响。 */
    default long fundingDeltaUnits(CoreInstrument instrument, long signedQuantitySteps,
                                   long markPriceTicks, long fundingRatePpm) {
        requireInstrument(instrument);
        throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                "funding is unsupported for " + productLine());
    }

    /** 统一生命周期结算边界使用的单持仓现金计算入口，具体产品决定采用哪套结算公式。 */
    default long lifecycleSettlementCashDeltaUnits(CoreInstrument instrument,
                                                   long signedQuantitySteps,
                                                   long entryPriceTicks,
                                                   long settlementPriceTicks) {
        requireInstrument(instrument);
        throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                "lifecycle settlement is unsupported for " + productLine());
    }

    static final ProductTradingRules SPOT = new Spot();
    static final ProductTradingRules LINEAR_PERPETUAL = new Perpetual(ContractType.LINEAR_PERPETUAL);
    static final ProductTradingRules INVERSE_PERPETUAL = new Perpetual(ContractType.INVERSE_PERPETUAL);
    static final ProductTradingRules LINEAR_DELIVERY = new Delivery(ContractType.LINEAR_DELIVERY);
    static final ProductTradingRules INVERSE_DELIVERY = new Delivery(ContractType.INVERSE_DELIVERY);
    static final ProductTradingRules OPTION = new OptionTradingRules();

    /** 根据币对真实合约类型选规则，并再次检查规则与币对匹配，防止跨产品使用。 */
    public static ProductTradingRules forInstrument(CoreInstrument instrument) {
        if (instrument == null) throw new IllegalArgumentException("settlement instrument is required");
        ProductTradingRules kernel = forProductLine(instrument.contractType().productLine());
        kernel.requireInstrument(instrument);
        return kernel;
    }

    /** 从六条产品线中选择固定规则。 */
    public static ProductTradingRules forProductLine(ProductLine productLine) {
        if (productLine == null) throw new IllegalArgumentException("product line is required");
        return switch (productLine) {
            case SPOT -> SPOT;
            case LINEAR_PERPETUAL -> LINEAR_PERPETUAL;
            case INVERSE_PERPETUAL -> INVERSE_PERPETUAL;
            case LINEAR_DELIVERY -> LINEAR_DELIVERY;
            case INVERSE_DELIVERY -> INVERSE_DELIVERY;
            case OPTION -> OPTION;
        };
    }

    final class Spot implements ProductTradingRules {
    /** 返回现货产品线，供命令和状态隔离检查使用。 */
    public ProductLine productLine() { return ProductLine.SPOT; }
    /** 返回本规则接受的现货合约类型，不允许借此处理永续、交割或期权。 */
    public ContractType contractType() { return ContractType.SPOT; }
    /** 计算现货订单需要预留的资产，买单同时覆盖手续费扣款所需额度。 */
    @Override
    public long reservationUnits(CoreInstrument instrument, Position position,
                                 OrderCommand.ResolvedPlaceOrder order, long leverage,
                                 long pendingQuantitySteps) {
        if (order.side() == CoreOrderSide.SELL) return CoreContractMath.spotBaseUnits(instrument, order.quantitySteps());
        long notional = CoreContractMath.spotQuoteUnits(instrument, order.reservationPriceTicks(), order.quantitySteps());
        long feeDebit = CoreContractMath.fragmentationSafeFeeDebit(instrument, order);
        return Math.addExact(notional, feeDebit);
    }
}

    abstract class AbstractFutures implements ProductTradingRules {
    protected final ContractType contractType;

    protected AbstractFutures(ContractType contractType) {
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
    public long reservationUnits(CoreInstrument instrument, Position position,
                                 OrderCommand.ResolvedPlaceOrder order, long leverage, long pendingQuantitySteps) {
        long current = position == null ? 0 : position.signedQuantitySteps();

        long openSteps = order.reduceOnly() ? 0 : order.quantitySteps();
        long projectedRisk = Math.addExact(Math.absExact(current), order.quantitySteps());
        long margin = openingMargin(instrument, projectedRisk, openSteps, order.reservationPriceTicks(), leverage);
        long feeDebit = CoreContractMath.fragmentationSafeFeeDebit(instrument, order);
        return Math.max(1, Math.addExact(margin, feeDebit));
    }

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

    final class Perpetual extends AbstractFutures {

    public Perpetual(ContractType contractType) {
        super(contractType);
        if (contractType != ContractType.LINEAR_PERPETUAL && contractType != ContractType.INVERSE_PERPETUAL) {
            throw new IllegalArgumentException("perpetual contract type is required");
        }
    }

    /** 永续只允许经过维护门禁批准的管理结算。 */
    @Override
    public void validateLifecycleSettlementProductRule(CoreInstrument instrument, SettleInstrumentCommand command) {
        if (!instrument.administrativeSettlement(command)) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "perpetual settlement requires an approved maintenance gate");
        }
    }

    @Override
    public long fundingDeltaUnits(CoreInstrument instrument, long quantity, long mark, long rate) {
        requireInstrument(instrument);
        return CoreContractMath.fundingDeltaUnits(instrument, quantity, mark, rate);
    }
}

    final class Delivery extends AbstractFutures {

    public Delivery(ContractType contractType) {
        super(contractType);
        if (contractType != ContractType.LINEAR_DELIVERY && contractType != ContractType.INVERSE_DELIVERY) {
            throw new IllegalArgumentException("delivery contract type is required");
        }
    }

    @Override
    public void validateLifecycleSettlementProductRule(CoreInstrument instrument, SettleInstrumentCommand command) {
    }

    @Override
    public long lifecycleSettlementCashDeltaUnits(CoreInstrument instrument, long quantity, long entry, long settlement) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, settlement);
    }

}

}
