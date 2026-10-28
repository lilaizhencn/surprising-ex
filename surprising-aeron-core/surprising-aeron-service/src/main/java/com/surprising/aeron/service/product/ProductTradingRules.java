package com.surprising.aeron.service.product;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

/**
 * 六条产品线共同使用的规则入口，只描述“怎么算”和“是否允许”，不拥有账户或订单状态。
 * 各产品分别实现冻结、盈亏、资金费和到期现金规则，不支持的能力默认明确拒绝。
 */
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
    long reservationUnits(CoreInstrument instrument, PositionRuntime position,
                          ResolvedPlaceOrder order, long leverage,
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
}
