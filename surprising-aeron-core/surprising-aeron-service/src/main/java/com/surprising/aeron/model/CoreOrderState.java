package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.order.state.OrderReservation;
import com.surprising.product.api.ProductLine;
import java.util.UUID;

/**
 * 普通订单的不可变业务记录，集中保存委托、成交进度、费率、状态和提交信息。只记录业务事实；实际撮合、资金冻结、账户结算和状态变化由原运行时流程负责。
 * @param orderId 普通订单在 Core 中的唯一编号
 * @param productLine 这笔业务所属的产品线，用来隔离现货、各类期货和期权
 * @param userId 这份状态所属的用户编号，必须为正数
 * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
 * @param side 订单的买入或卖出方向
 * @param priceTicks 委托价格，单位为最小价格 tick，具体可否为零由订单类型决定
 * @param matchingPriceTicks 实际交给撮合的价格 tick，与对外保存的委托价格分别记录
 * @param quantitySteps 委托总数量，以币对配置的最小数量步数表示
 * @param executedQuantitySteps 已经成交的累计数量步数
 * @param reduceOnly 是否只允许减少已有持仓，不能借这笔订单增加反向或同向风险
 * @param marginMode 使用全仓还是逐仓保证金；两种模式的可用权益不能混算
 * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
 * @param orderType 限价、市价等执行订单类型
 * @param timeInForce 订单的有效方式，例如持续挂单、立即成交或撤销、只做挂单方
 * @param postOnly 是否要求只做挂单方，不能立即吃掉对手盘
 * @param clientOrderId 客户端提供的订单标识，便于查询和关联请求
 * @param commandId 产生该订单的命令唯一标识，用来关联处理结果
 * @param makerFeeRatePpm 挂单成交时采用的费率，单位为百万分之一，负数表示返佣
 * @param takerFeeRatePpm 吃单成交时采用的费率，单位为百万分之一，负数表示返佣
 * @param cumulativeFeeUnits 随成交累计的费用金额，沿用成交结算传入的正负口径，单位为结算资产最小单位
 * @param executedValueHigh 累计成交价格乘数量的宽整数高位；未提供完整成交额的构造路径用 -1 标记
 * @param executedValueLow 累计成交价格乘数量的宽整数低位，与高位一起保存成交额精度
 * @param createdAtEpochMillis 这份业务记录的创建时间，使用 Unix 毫秒时间戳
 * @param updatedAtEpochMillis 最后一次更新这份记录的 Unix 毫秒时间戳
 * @param clusterPosition 这份订单提交时对应的集群日志位置，0 表示尚未写入具体提交位置
 * @param status 订单当前的有效、撤销、全部成交或拒绝状态
 * @param revision 这份状态的修订号，供结果发布和后续更新判断版本
 */
public record CoreOrderState(
        long orderId,
        ProductLine productLine,
        long userId,
        String instrumentId,
        CoreOrderSide side,
        long priceTicks,
        long matchingPriceTicks,
        long quantitySteps,
        long executedQuantitySteps,
        boolean reduceOnly,
        CoreMarginMode marginMode,
        CorePositionSide positionSide,
        CoreOrderType orderType,
        CoreTimeInForce timeInForce,
        boolean postOnly,
        String clientOrderId,
        UUID commandId,
        long makerFeeRatePpm,
        long takerFeeRatePpm,
        long cumulativeFeeUnits,
        long executedValueHigh,
        long executedValueLow,
        long createdAtEpochMillis,
        long updatedAtEpochMillis,
        long clusterPosition,
        CoreOrderStatus status,
        long revision) {

    /**
     * 检查订单标识和属性，要求累计成交量处于零和总量之间；有效订单必须仍有未成交量，只挂单属性必须与限价 GTX 一致。
     */
    public CoreOrderState {
        if (orderId <= 0 || productLine == null || userId <= 0
                || side == null || priceTicks < 0 || matchingPriceTicks < 0
                || quantitySteps <= 0 || executedQuantitySteps < 0 || executedQuantitySteps > quantitySteps
                || marginMode == null || positionSide == null || orderType == null || timeInForce == null
                || clientOrderId == null || clientOrderId.length() > 64 || commandId == null
                || createdAtEpochMillis < 0 || updatedAtEpochMillis < createdAtEpochMillis || clusterPosition < 0
                || postOnly && (orderType != CoreOrderType.LIMIT || timeInForce != CoreTimeInForce.GTX)
                || status == null || revision <= 0) {
            throw new IllegalArgumentException("invalid order state");
        }
        instrumentId = OrderReservation.requireInstrumentId(instrumentId);
        if (status == CoreOrderStatus.OPEN && executedQuantitySteps == quantitySteps) {
            throw new IllegalArgumentException("open order must have remaining quantity");
        }
    }

    /** 返回尚未成交的历史数量，撤单后仍保留；由总量和累计成交量推导，不另存副本。 */
    public long remainingQuantitySteps() {
        return Math.subtractExact(quantitySteps, executedQuantitySteps);
    }

    /**
     * 创建只提供基本委托和进度的全仓净持仓限价 GTC 记录；撮合价沿用委托价，命令编号由订单号组成，其余费用和时间从 0 开始。
     * @param orderId 普通订单在 Core 中的唯一编号
     * @param productLine 这笔业务所属的产品线，用来隔离现货、各类期货和期权
     * @param userId 这份状态所属的用户编号，必须为正数
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param side 订单的买入或卖出方向
     * @param priceTicks 委托价格，单位为最小价格 tick，具体可否为零由订单类型决定
     * @param quantitySteps 委托总数量，以币对配置的最小数量步数表示
     * @param executedQuantitySteps 已经成交的累计数量步数
     * @param reduceOnly 是否只允许减少已有持仓，不能借这笔订单增加反向或同向风险
     * @param status 订单当前的有效、撤销、全部成交或拒绝状态
     * @param revision 这份状态的修订号，供结果发布和后续更新判断版本
     */
    public CoreOrderState(long orderId, ProductLine productLine, long userId, String instrumentId,
                          CoreOrderSide side, long priceTicks, long quantitySteps,
                          long executedQuantitySteps, boolean reduceOnly,
                          CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, instrumentId, side, priceTicks, quantitySteps,
                executedQuantitySteps, reduceOnly, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "", new UUID(0, orderId),
                0, 0, status, revision);
    }

    /**
     * 创建撮合价与委托价相同、尚未附上提交时间及位置的订单，累计费用和提交信息从 0 开始。
     * @param orderId 普通订单在 Core 中的唯一编号
     * @param productLine 这笔业务所属的产品线，用来隔离现货、各类期货和期权
     * @param userId 这份状态所属的用户编号，必须为正数
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param side 订单的买入或卖出方向
     * @param priceTicks 委托价格，单位为最小价格 tick，具体可否为零由订单类型决定
     * @param quantitySteps 委托总数量，以币对配置的最小数量步数表示
     * @param executedQuantitySteps 已经成交的累计数量步数
     * @param reduceOnly 是否只允许减少已有持仓，不能借这笔订单增加反向或同向风险
     * @param marginMode 使用全仓还是逐仓保证金；两种模式的可用权益不能混算
     * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
     * @param orderType 限价、市价等执行订单类型
     * @param timeInForce 订单的有效方式，例如持续挂单、立即成交或撤销、只做挂单方
     * @param postOnly 是否要求只做挂单方，不能立即吃掉对手盘
     * @param clientOrderId 客户端提供的订单标识，便于查询和关联请求
     * @param commandId 产生该订单的命令唯一标识，用来关联处理结果
     * @param makerFeeRatePpm 挂单成交时采用的费率，单位为百万分之一，负数表示返佣
     * @param takerFeeRatePpm 吃单成交时采用的费率，单位为百万分之一，负数表示返佣
     * @param status 订单当前的有效、撤销、全部成交或拒绝状态
     * @param revision 这份状态的修订号，供结果发布和后续更新判断版本
     */
    public CoreOrderState(long orderId, ProductLine productLine, long userId, String instrumentId,
                          CoreOrderSide side, long priceTicks, long quantitySteps,
                          long executedQuantitySteps, boolean reduceOnly,
                          CoreMarginMode marginMode, CorePositionSide positionSide, CoreOrderType orderType,
                          CoreTimeInForce timeInForce, boolean postOnly, String clientOrderId, UUID commandId,
                          long makerFeeRatePpm, long takerFeeRatePpm, CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, instrumentId, side, priceTicks, priceTicks, quantitySteps,
                executedQuantitySteps, reduceOnly, marginMode, positionSide, orderType, timeInForce,
                postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm, 0,
                executedQuantitySteps == 0 ? 0 : -1, 0, 0, 0, 0, status, revision);
    }

    /**
     * 创建指定保证金模式和持仓腿的限价 GTC 记录，撮合价沿用委托价，其余可省略属性采用构造器明确给出的初始值。
     * @param orderId 普通订单在 Core 中的唯一编号
     * @param productLine 这笔业务所属的产品线，用来隔离现货、各类期货和期权
     * @param userId 这份状态所属的用户编号，必须为正数
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param side 订单的买入或卖出方向
     * @param priceTicks 委托价格，单位为最小价格 tick，具体可否为零由订单类型决定
     * @param quantitySteps 委托总数量，以币对配置的最小数量步数表示
     * @param executedQuantitySteps 已经成交的累计数量步数
     * @param reduceOnly 是否只允许减少已有持仓，不能借这笔订单增加反向或同向风险
     * @param marginMode 使用全仓还是逐仓保证金；两种模式的可用权益不能混算
     * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
     * @param status 订单当前的有效、撤销、全部成交或拒绝状态
     * @param revision 这份状态的修订号，供结果发布和后续更新判断版本
     */
    public CoreOrderState(long orderId, ProductLine productLine, long userId, String instrumentId,
                          CoreOrderSide side, long priceTicks, long quantitySteps,
                          long executedQuantitySteps, boolean reduceOnly,
                          CoreMarginMode marginMode, CorePositionSide positionSide,
                          CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, instrumentId, side, priceTicks, quantitySteps,
                executedQuantitySteps, reduceOnly, marginMode, positionSide,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "", new UUID(0, orderId),
                0, 0, status, revision);
    }
}
