package com.surprising.aeron.service.order.admission;

import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.order.state.OrderRuntime;

/**
 * Owner 固定的批量下单输入与命令槽访问边界。账户 Lane 逐项取意图和决策，在既有命令槽写解析输入及获准订单租约；Lane 完成前调用方不能复用这些输入。
 */
public interface PlaceBatchIntentSource {
    /**
     * 读取指定批次项的原始下单意图，不提前在 Owner 执行账户准入。
     * @param index 当前批次项的零起始下标
     */
    PlaceOrderCommand intent(int index);
    /**
     * 读取该项已固定的币对市场决策及资产身份输入。
     * @param index 当前批次项的零起始下标
     */
    Decision decision(int index);
    /**
     * 借用该项原有命令槽中的决策对象，供 Lane 填入解析结果，不能跨项覆盖尚在使用的输入。
     * @param index 当前批次项的零起始下标
     */
    ResolvedPlaceOrder resolvedOrderSlot(int index);
    /**
     * 让既有批次命令槽接管或保存该项获准订单的租约，返回后续撮合需要保留的订单输入，生命周期由原批次负责。
     * @param index 当前批次项的零起始下标
     * @param order 所属 Lane 刚完成冻结的订单输入，交给该项命令槽保留
     */
    OrderRuntime captureAdmittedOrder(int index, OrderRuntime order);
    /**
     * 批次中某币对共用的决策输入与资产路由，Owner 预先准备；整批准入可直接使用其中 OI，顺序项按自身交接流程取得需要的账本 OI。
     * @param context 当前命令固定的币对、市场、费率和账本结算上下文
     * @param openInterestSteps Owner 为批次决策固定的币对 OI 数量步数
     * @param baseAssetId 现货卖单使用的基础资产运行时编号
     * @param quoteAssetId 现货买单使用的报价资产运行时编号
     * @param settleAssetId 衍生品保证金使用的结算资产运行时编号
     */
    record Decision(CoreOrderDecisionResolver.Context context, long openInterestSteps,
                    int baseAssetId, int quoteAssetId, int settleAssetId) { }
}
