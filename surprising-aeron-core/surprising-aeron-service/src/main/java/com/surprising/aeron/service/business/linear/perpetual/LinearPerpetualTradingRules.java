package com.surprising.aeron.service.business.linear.perpetual;

import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.business.ProductTradingRules;
import com.surprising.aeron.service.business.derivative.FuturesOrderAdmission;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.math.CoreContractMath;
import com.surprising.aeron.service.state.position.PositionRuntime;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

/** U 本位永续的交易规则入口；按线性合约口径计算盈亏、冻结和生命周期现金变化。 */
public final class LinearPerpetualTradingRules implements ProductTradingRules {
    /**
     * 返回U 本位永续的产品线标识，供运行时隔离和规则选择使用。
     */
    public ProductLine productLine() { return ProductLine.LINEAR_PERPETUAL; }
    /**
     * 返回本规则允许处理的U 本位永续合约类型，避免混入其他产品配置。
     */
    public ContractType contractType() { return ContractType.LINEAR_PERPETUAL; }
    /**
     * 永续没有普通到期交割，只接受经过批准维护门禁的管理结算，否则返回业务拒绝。
     * @param instrument 当前产品的合约配置，也用于检查维护结算条件
     * @param command 本次到期或维护结算命令
     */
    @Override
    public void validateLifecycleSettlementProductRule(CoreInstrument instrument,
                                                        SettleInstrumentCommand command) {
        if (!instrument.administrativeSettlement(command)) {
            throw new CoreStateRejectedException("PRODUCT_LINE_UNSUPPORTED",
                    "perpetual settlement requires an approved maintenance gate");
        }
    }
    /**
     * 先核对合约类型，再按线性合约公式计算这部分持仓的已实现盈亏。
     * @param instrument U 本位永续合约配置，包含价格和结算资产的精度
     * @param quantity 本次计算的有符号仓位数量，单位为数量步数
     * @param entry 持仓入场价格，单位为价格 tick
     * @param execution 平仓或成交价格，单位为价格 tick
     * @return 结算资产的最小整数单位；正数为收益，负数为亏损
     */
    public long realizedPnlUnits(CoreInstrument instrument, long quantity, long entry, long execution) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, execution);
    }
    /**
     * 按当前仓位方向、标记价和资金费率计算U 本位永续资金费对账户的影响。
     * @param instrument U 本位永续合约配置
     * @param quantity 带方向的仓位数量，单位为数量步数
     * @param mark 资金费使用的标记价格，单位为价格 tick
     * @param rate 本期资金费率，单位为百万分之一
     * @return 结算资产的最小整数单位；正数入账，负数扣款
     */
    public long fundingDeltaUnits(CoreInstrument instrument, long quantity, long mark, long rate) {
        requireInstrument(instrument);
        return CoreContractMath.fundingDeltaUnits(instrument, quantity, mark, rate);
    }
    /**
     * 给统一生命周期结算入口提供U 本位永续的现金变化计算，不修改账户状态。
     * @param instrument U 本位永续合约配置
     * @param quantity 带方向的待结算数量，单位为数量步数
     * @param entry 持仓入场价格，单位为价格 tick
     * @param settlement 本次维护或到期结算价格，单位为价格 tick
     * @return 需要由所属账户应用的结算资产增减金额
     */
    @Override
    public long lifecycleSettlementCashDeltaUnits(CoreInstrument instrument,
                                                  long quantity, long entry, long settlement) {
        return realizedPnlUnits(instrument, quantity, entry, settlement);
    }
    /**
     * 把U 本位永续订单的冻结金额交给期货准入公式计算，仍使用本产品的合约配置。
     * @param instrument U 本位永续合约配置
     * @param position 该用户当前对应持仓，没有持仓时为 null
     * @param order 已解析并带有费率、价格和保证金模式的订单
     * @param leverage 当前杠杆倍数，按一百万倍缩放保存
     * @param pendingQuantitySteps 统一接口传入的其他在途订单数量；当前期货计算没有读取此参数
     * @return 需预留的结算资产金额，使用最小整数单位
     */
    @Override
    public long reservationUnits(CoreInstrument instrument, PositionRuntime position,
                                 ResolvedPlaceOrder order, long leverage,
                                 long pendingQuantitySteps) {
        return FuturesOrderAdmission.reservationUnits(
                instrument, position, order, leverage, pendingQuantitySteps);
    }
}
