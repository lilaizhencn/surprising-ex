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

    /**
     * 计算下单所需预留，不在计算过程中实际扣款或创建订单。
     * @param instrument 当前币对的合约、资产、精度与风险配置
     * @param position 当前对应持仓，没有持仓时可为 null
     * @param order 已解析并固定业务参数的订单
     * @param leverage 杠杆倍数，按一百万倍缩放保存；不使用杠杆的产品可以忽略
     * @param pendingQuantitySteps 本次风险评估需要计入的其他在途委托数量，单位为数量步数
     * @return 所属预留资产的最小整数金额，资产由产品和买卖方向决定
     */
    long reservationUnits(CoreInstrument instrument, Position position,
                          OrderCommand.ResolvedPlaceOrder order, long leverage,
                          long pendingQuantitySteps);

    /** 返回本规则唯一支持的产品线，用于隔离运行时与账户。 */
    ProductLine productLine();

    /** 返回本规则唯一支持的合约类型，用于与币对配置再次核对。 */
    ContractType contractType();

    /**
     * 在撤销订单和派发账户结算之前，先检查产品、维护任务批准条件与结算价格。
     * 本方法只做规则判断；日志顺序、撤单和实际状态写入仍由运行时负责。
     * @param instrument 将要处理生命周期结算的币对配置
     * @param command 到期或维护结算命令，不能为 null
     */
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

    /**
     * 给具体产品增加自己的生命周期准入条件；未实现此能力的产品默认拒绝。
     * @param instrument 已由公共入口核对过产品类型的币对配置
     * @param command 正在检查的到期或维护结算命令
     */
    default void validateLifecycleSettlementProductRule(CoreInstrument instrument,
                                                        SettleInstrumentCommand command) {
        throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                "instrument settlement is unsupported for " + productLine());
    }

    /**
     * 同时核对合约类型和产品线，阻止把另一条产品线的配置交给当前资金公式。
     * @param instrument 调用本产品规则前需要核对的币对配置
     */
    default void requireInstrument(CoreInstrument instrument) {
        if (instrument.contractType() != contractType()
                || instrument.contractType().productLine() != productLine()) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "instrument does not belong to settlement kernel " + productLine());
        }
    }

    /**
     * 计算权利金现金变化；默认产品没有这项现金收支，核对币对后返回 0，期权会覆盖此方法。
     * @param instrument 当前币对配置，必须属于本规则支持的产品
     * @param side 买卖方向，期权实现据此区分支付和收取权利金
     * @param priceTicks 权利金价格，单位为价格 tick
     * @param quantitySteps 发生权利金收支的数量，单位为数量步数
     */
    default long premiumDeltaUnits(CoreInstrument instrument, CoreOrderSide side,
                                   long priceTicks, long quantitySteps) {
        requireInstrument(instrument);
        return 0;
    }

    /**
     * 计算平仓产生的已实现盈亏；默认拒绝，由支持该口径的产品提供实现。
     * @param instrument 当前合约的价格、数量和结算资产精度
     * @param signedCloseSteps 要结算的有符号持仓数量，单位为数量步数
     * @param entryPriceTicks 原持仓入场价格，单位为价格 tick
     * @param executionPriceTicks 本次平仓成交价格，单位为价格 tick
     * @return 结算资产的最小整数金额，正数表示收益，负数表示亏损
     */
    default long realizedPnlUnits(CoreInstrument instrument, long signedCloseSteps,
                                  long entryPriceTicks, long executionPriceTicks) {
        requireInstrument(instrument);
        throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                "realized PnL is unsupported for " + productLine());
    }

    /**
     * 计算资金费对账户的现金影响；默认拒绝，永续产品分别实现自己的计算。
     * @param instrument 当前永续合约的结算配置
     * @param signedQuantitySteps 带方向的持仓数量，单位为数量步数
     * @param markPriceTicks 本期资金费使用的标记价格，单位为价格 tick
     * @param fundingRatePpm 资金费率，单位为百万分之一
     * @return 结算资产最小整数单位表示的现金增减，正数入账、负数扣款
     */
    default long fundingDeltaUnits(CoreInstrument instrument, long signedQuantitySteps,
                                   long markPriceTicks, long fundingRatePpm) {
        requireInstrument(instrument);
        throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                "funding is unsupported for " + productLine());
    }

    /**
     * 统一生命周期结算边界使用的单持仓现金计算入口，具体产品决定采用哪套结算公式。
     * @param instrument 本次生命周期结算的合约配置
     * @param signedQuantitySteps 带方向的待结算持仓数量，单位为数量步数
     * @param entryPriceTicks 持仓入场价格，单位为价格 tick
     * @param settlementPriceTicks 本次到期或维护结算价格，单位为价格 tick
     * @return 账户后续需要应用的结算资产现金增减，使用最小整数单位
     */
    default long lifecycleSettlementCashDeltaUnits(CoreInstrument instrument,
                                                   long signedQuantitySteps,
                                                   long entryPriceTicks,
                                                   long settlementPriceTicks) {
        requireInstrument(instrument);
        throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                "lifecycle settlement is unsupported for " + productLine());
    }


    static final ProductTradingRules SPOT = new Spot();
    /** U 本位永续规则，使用线性合约盈亏、资金费和准入计算。 */
    static final ProductTradingRules LINEAR_PERPETUAL = new Perpetual(ContractType.LINEAR_PERPETUAL);
    /** 币本位永续规则，使用反向合约盈亏、资金费和准入计算。 */
    static final ProductTradingRules INVERSE_PERPETUAL = new Perpetual(ContractType.INVERSE_PERPETUAL);
    /** U 本位交割规则，处理线性合约交易及到期或维护结算。 */
    static final ProductTradingRules LINEAR_DELIVERY = new Delivery(ContractType.LINEAR_DELIVERY);
    /** 币本位交割规则，处理反向合约交易及到期或维护结算。 */
    static final ProductTradingRules INVERSE_DELIVERY = new Delivery(ContractType.INVERSE_DELIVERY);
    /** 期权规则，区分权利金现金收支、卖方保证金和到期现金结算。 */
    static final ProductTradingRules OPTION = new OptionTradingRules();

    /**
     * 根据币对真实合约类型选规则，并再次检查规则与币对匹配，防止跨产品使用。
     * @param instrument 本次交易或结算使用的币对配置，不能为 null
     * @return 与该币对产品线一致的共用规则对象
     */
    public static ProductTradingRules forInstrument(CoreInstrument instrument) {
        if (instrument == null) throw new IllegalArgumentException("settlement instrument is required");
        // 先按合约所属产品线选规则，再由规则自身核对合约类型，不能只信调用方的产品标记。
        ProductTradingRules kernel = forProductLine(instrument.contractType().productLine());
        kernel.requireInstrument(instrument);
        return kernel;
    }

    /**
     * 从六条产品线中选择固定规则；没有产品线就直接报错，不默认改用现货或另一类合约。
     * @param productLine 当前命令或运行时已经确定的产品线
     * @return 这条产品线专属的交易规则
     */
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
        // 现有持仓的带方向数量；没有持仓时以 0 参与风险规模计算。
        long current = position == null ? 0 : position.signedQuantitySteps();

        // 只减仓不预留开仓保证金；其他订单按整个委托数量计算开仓部分。
        long openSteps = order.reduceOnly() ? 0 : order.quantitySteps();
        // 用已有持仓绝对量加新单数量选风险档位，不把这个估算值当作真实成交后持仓。
        long projectedRisk = Math.addExact(Math.absExact(current), order.quantitySteps());
        // 按合约风险档位、指定杠杆和冻结价格得到开仓保证金。
        long margin = openingMargin(instrument, projectedRisk, openSteps, order.reservationPriceTicks(), leverage);
        // 覆盖订单拆成多次成交时的费用舍入，返佣不抵减这项冻结。
        long feeDebit = CoreContractMath.fragmentationSafeFeeDebit(instrument, order);
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
        // 公共入口已经核对产品、维护批准条件及结算价，无额外的交割产品门槛。
    }

    @Override
    public long lifecycleSettlementCashDeltaUnits(CoreInstrument instrument, long quantity, long entry, long settlement) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, settlement);
    }

}

}
