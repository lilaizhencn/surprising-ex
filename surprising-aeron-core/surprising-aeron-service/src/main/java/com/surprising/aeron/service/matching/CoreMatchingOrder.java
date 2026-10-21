package com.surprising.aeron.service.matching;

import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CoreTimeInForce;

/**
 * 交给订单簿执行的一笔最小撮合输入，只携带撮合需要的身份、价格和数量，不携带账户可变状态。
 * @param orderId 已通过准入的正数订单编号
 * @param instrumentId 订单簿使用的正数币对编号
 * @param side 买入或卖出方向
 * @param orderType 限价、市价等订单类型
 * @param timeInForce 订单有效方式，例如 GTC 或 IOC
 * @param matchingPriceTicks 撮合参考价格或限价，以币对的最小价格单位表示，必须为正
 * @param quantitySteps 委托数量，以币对的最小数量单位表示，必须为正
 * @param maxSlippagePpm 允许的最大滑点，百万分之一为单位，范围从 0 到小于一百万
 */
public record CoreMatchingOrder(long orderId, int instrumentId, CoreOrderSide side, CoreOrderType orderType,
                                CoreTimeInForce timeInForce, long matchingPriceTicks, long quantitySteps, long maxSlippagePpm) {
    /** 创建时拒绝不完整身份、非法价格数量及越界滑点，避免坏输入进入订单簿。 */
    public CoreMatchingOrder {
        if (orderId <= 0 || instrumentId <= 0 || side == null || orderType == null
                || timeInForce == null || matchingPriceTicks <= 0 || quantitySteps <= 0
                || maxSlippagePpm < 0 || maxSlippagePpm >= 1_000_000) {
            throw new IllegalArgumentException("invalid Core matching order");
        }
    }
    /**
     * 接受字符串币对编号的便捷入口，未指定滑点时使用 0。
     * @param orderId 正数订单编号
     * @param instrumentId 能转换成正整数的币对编号字符串
     * @param side 买入或卖出方向
     * @param orderType 委托类型
     * @param timeInForce 订单有效方式
     * @param matchingPriceTicks 以最小价格单位表示的撮合价格
     * @param quantitySteps 以最小数量单位表示的委托数量
     */
    public CoreMatchingOrder(long orderId, String instrumentId, CoreOrderSide side, CoreOrderType orderType,
            CoreTimeInForce timeInForce, long matchingPriceTicks, long quantitySteps) {
        this(orderId, instrumentId, side, orderType, timeInForce, matchingPriceTicks, quantitySteps, 0);
    }

    /**
     * 把字符串币对编号转换成订单簿使用的整数编号，其他校验交给标准构造器。
     * @param orderId 正数订单编号
     * @param instrumentId 能转换成正整数的币对编号字符串
     * @param side 买入或卖出方向
     * @param orderType 委托类型
     * @param timeInForce 订单有效方式
     * @param matchingPriceTicks 以最小价格单位表示的撮合价格
     * @param quantitySteps 以最小数量单位表示的委托数量
     * @param maxSlippagePpm 以百万分之一表示的最大滑点
     */
    public CoreMatchingOrder(long orderId, String instrumentId, CoreOrderSide side, CoreOrderType orderType,
            CoreTimeInForce timeInForce, long matchingPriceTicks, long quantitySteps, long maxSlippagePpm) {
        this(orderId, com.surprising.product.api.InstrumentIds.parse(instrumentId), side, orderType,
                timeInForce, matchingPriceTicks, quantitySteps, maxSlippagePpm);
    }

    /**
     * 返回这条辅助规则规定的产品滑点：U 本位永续和交割为 100 ppm，其他产品为 0。
     * @param product 正在构造撮合订单的产品线
     * @return 以百万分之一为单位的滑点数值，不是百分比整数
     */
    public static long slippagePpm(com.surprising.product.api.ProductLine product) {
        return product == com.surprising.product.api.ProductLine.LINEAR_PERPETUAL
                || product == com.surprising.product.api.ProductLine.LINEAR_DELIVERY ? 100 : 0;
    }
}
