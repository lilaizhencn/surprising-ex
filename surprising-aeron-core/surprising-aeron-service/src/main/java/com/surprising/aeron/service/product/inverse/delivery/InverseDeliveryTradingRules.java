package com.surprising.aeron.service.product.inverse.delivery;

import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.order.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.service.product.ProductTradingRules;
import com.surprising.aeron.service.product.derivative.FuturesOrderAdmission;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;

/** 币本位交割的交易规则入口；按反向合约口径计算盈亏、冻结和生命周期现金变化。 */
public final class InverseDeliveryTradingRules implements ProductTradingRules {
    /**
     * 返回币本位交割的产品线标识，供运行时隔离和规则选择使用。
     */
    public ProductLine productLine() { return ProductLine.INVERSE_DELIVERY; }
    /**
     * 返回本规则允许处理的币本位交割合约类型，避免混入其他产品配置。
     */
    public ContractType contractType() { return ContractType.INVERSE_DELIVERY; }
    /**
     * 本产品不增加额外到期门槛；公共维护和生命周期校验由统一入口负责，这里不重复校验。
     * @param instrument 当前产品的合约配置，也用于检查维护结算条件
     * @param command 本次到期或维护结算命令
     */
    @Override
    public void validateLifecycleSettlementProductRule(CoreInstrument instrument,
                                                        SettleInstrumentCommand command) {
        // 交割结算由公共维护校验负责准入，此产品分支不重复增加检查。
    }
    /**
     * 先核对合约类型，再按反向合约公式计算这部分持仓的已实现盈亏。
     * @param instrument 币本位交割合约配置，包含价格和结算资产的精度
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
     * 把反向合约从入场价到结算价的盈亏作为本次交割现金变化。
     * @param instrument 币本位交割合约配置
     * @param quantity 带方向的待结算持仓数量，单位为数量步数
     * @param entry 持仓入场价格，单位为价格 tick
     * @param settlement 本次采用的结算价格，单位为价格 tick
     * @return 结算资产最小整数单位表示的现金增减
     */
    public long lifecycleCashDeltaUnits(CoreInstrument instrument, long quantity, long entry, long settlement) {
        requireInstrument(instrument);
        return CoreContractMath.pnlUnits(instrument, quantity, entry, settlement);
    }
    /**
     * 给统一生命周期结算入口提供币本位交割的现金变化计算，不修改账户状态。
     * @param instrument 币本位交割合约配置
     * @param quantity 带方向的待结算数量，单位为数量步数
     * @param entry 持仓入场价格，单位为价格 tick
     * @param settlement 本次维护或到期结算价格，单位为价格 tick
     * @return 需要由所属账户应用的结算资产增减金额
     */
    @Override
    public long lifecycleSettlementCashDeltaUnits(CoreInstrument instrument,
                                                  long quantity, long entry, long settlement) {
        return lifecycleCashDeltaUnits(instrument, quantity, entry, settlement);
    }
    /**
     * 把币本位交割订单的冻结金额交给期货准入公式计算，仍使用本产品的合约配置。
     * @param instrument 币本位交割合约配置
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
