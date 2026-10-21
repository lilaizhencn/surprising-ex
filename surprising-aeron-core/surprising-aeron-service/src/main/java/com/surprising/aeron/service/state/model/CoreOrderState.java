package com.surprising.aeron.service.state.model;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.state.order.OrderReservation;
import com.surprising.product.api.ProductLine;
import java.util.UUID;

/**
 * 普通订单的不可变业务记录，集中保存委托、成交进度、费率、状态和提交信息。状态变化返回新记录；实际撮合、资金冻结和账户结算由各自流程负责。
 * @param orderId 普通订单在 Core 中的唯一编号
 * @param productLine 这笔业务所属的产品线，用来隔离现货、各类期货和期权
 * @param userId 这份状态所属的用户编号，必须为正数
 * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
 * @param side 订单的买入或卖出方向
 * @param priceTicks 委托价格，单位为最小价格 tick，具体可否为零由订单类型决定
 * @param matchingPriceTicks 实际交给撮合的价格 tick，与对外保存的委托价格分别记录
 * @param quantitySteps 委托总数量，以币对配置的最小数量步数表示
 * @param executedQuantitySteps 已经成交的累计数量步数
 * @param remainingQuantitySteps 尚未成交的数量步数；订单撤销后也保留这部分历史数量
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
        long remainingQuantitySteps,
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
     * 用累计费用等业务字段创建订单，但不接收完整成交额；未成交时成交额记 0，已有成交时用高位 -1 标记成交额未提供。
     * @param orderId 普通订单在 Core 中的唯一编号
     * @param productLine 这笔业务所属的产品线，用来隔离现货、各类期货和期权
     * @param userId 这份状态所属的用户编号，必须为正数
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param side 订单的买入或卖出方向
     * @param priceTicks 委托价格，单位为最小价格 tick，具体可否为零由订单类型决定
     * @param matchingPriceTicks 实际交给撮合的价格 tick，与对外保存的委托价格分别记录
     * @param quantitySteps 委托总数量，以币对配置的最小数量步数表示
     * @param executedQuantitySteps 已经成交的累计数量步数
     * @param remainingQuantitySteps 尚未成交的数量步数；订单撤销后也保留这部分历史数量
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
     * @param createdAtEpochMillis 这份业务记录的创建时间，使用 Unix 毫秒时间戳
     * @param updatedAtEpochMillis 最后一次更新这份记录的 Unix 毫秒时间戳
     * @param clusterPosition 这份订单提交时对应的集群日志位置，0 表示尚未写入具体提交位置
     * @param status 订单当前的有效、撤销、全部成交或拒绝状态
     * @param revision 这份状态的修订号，供结果发布和后续更新判断版本
     */
    public CoreOrderState(
        long orderId,
        ProductLine productLine,
        long userId,
        String instrumentId,
        CoreOrderSide side,
        long priceTicks,
        long matchingPriceTicks,
        long quantitySteps,
        long executedQuantitySteps,
        long remainingQuantitySteps,
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
        long createdAtEpochMillis,
        long updatedAtEpochMillis,
        long clusterPosition,
        CoreOrderStatus status,
        long revision) {
        this(orderId, productLine, userId, instrumentId, side, priceTicks, matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm, cumulativeFeeUnits, executedQuantitySteps == 0 ? 0 : -1, 0, createdAtEpochMillis, updatedAtEpochMillis, clusterPosition, status, revision);
    }

    /**
     * 检查订单标识和属性，要求累计成交量加未成交量等于总量；有效订单必须仍有未成交量，只挂单属性必须与限价 GTX 一致。
     */
    public CoreOrderState {
        if (orderId <= 0 || productLine == null || userId <= 0
                || side == null || priceTicks < 0 || matchingPriceTicks < 0
                || quantitySteps <= 0 || executedQuantitySteps < 0 || remainingQuantitySteps < 0
                || Math.addExact(executedQuantitySteps, remainingQuantitySteps) != quantitySteps
                || marginMode == null || positionSide == null || orderType == null || timeInForce == null
                || clientOrderId == null || clientOrderId.length() > 64 || commandId == null
                || createdAtEpochMillis < 0 || updatedAtEpochMillis < createdAtEpochMillis || clusterPosition < 0
                || postOnly && (orderType != CoreOrderType.LIMIT || timeInForce != CoreTimeInForce.GTX)
                || status == null || revision <= 0) {
            throw new IllegalArgumentException("invalid order state");
        }
        instrumentId = OrderReservation.requireInstrumentId(instrumentId);
        if (status == CoreOrderStatus.OPEN && remainingQuantitySteps == 0) {
            throw new IllegalArgumentException("open order must have remaining quantity");
        }
    }

    /**
     * 创建带完整订单属性和提交信息的记录，累计费用从 0 开始，成交额按已有成交量是否为零设置标记。
     * @param orderId 普通订单在 Core 中的唯一编号
     * @param productLine 这笔业务所属的产品线，用来隔离现货、各类期货和期权
     * @param userId 这份状态所属的用户编号，必须为正数
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param side 订单的买入或卖出方向
     * @param priceTicks 委托价格，单位为最小价格 tick，具体可否为零由订单类型决定
     * @param matchingPriceTicks 实际交给撮合的价格 tick，与对外保存的委托价格分别记录
     * @param quantitySteps 委托总数量，以币对配置的最小数量步数表示
     * @param executedQuantitySteps 已经成交的累计数量步数
     * @param remainingQuantitySteps 尚未成交的数量步数；订单撤销后也保留这部分历史数量
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
     * @param createdAtEpochMillis 这份业务记录的创建时间，使用 Unix 毫秒时间戳
     * @param updatedAtEpochMillis 最后一次更新这份记录的 Unix 毫秒时间戳
     * @param clusterPosition 这份订单提交时对应的集群日志位置，0 表示尚未写入具体提交位置
     * @param status 订单当前的有效、撤销、全部成交或拒绝状态
     * @param revision 这份状态的修订号，供结果发布和后续更新判断版本
     */
    public CoreOrderState(long orderId, ProductLine productLine, long userId, String instrumentId,
                          CoreOrderSide side, long priceTicks, long matchingPriceTicks,
                          long quantitySteps, long executedQuantitySteps, long remainingQuantitySteps,
                          boolean reduceOnly, CoreMarginMode marginMode, CorePositionSide positionSide,
                          CoreOrderType orderType, CoreTimeInForce timeInForce, boolean postOnly,
                          String clientOrderId, UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                          long createdAtEpochMillis, long updatedAtEpochMillis, long clusterPosition,
                          CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, instrumentId, side, priceTicks, matchingPriceTicks,
                quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                0, createdAtEpochMillis, updatedAtEpochMillis, clusterPosition, status, revision);
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
     * @param remainingQuantitySteps 尚未成交的数量步数；订单撤销后也保留这部分历史数量
     * @param reduceOnly 是否只允许减少已有持仓，不能借这笔订单增加反向或同向风险
     * @param status 订单当前的有效、撤销、全部成交或拒绝状态
     * @param revision 这份状态的修订号，供结果发布和后续更新判断版本
     */
    public CoreOrderState(long orderId, ProductLine productLine, long userId, String instrumentId,
                          CoreOrderSide side, long priceTicks, long quantitySteps,
                          long executedQuantitySteps, long remainingQuantitySteps, boolean reduceOnly,
                          CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, instrumentId, side, priceTicks, quantitySteps,
                executedQuantitySteps, remainingQuantitySteps, reduceOnly, CoreMarginMode.CROSS,
                CorePositionSide.NET, CoreOrderType.LIMIT, CoreTimeInForce.GTC, false,
                "", new UUID(0, orderId), 0, 0, 0, 0, 0, status, revision);
    }

    /**
     * 创建撮合价与委托价相同的订单，并保留传入的订单属性、费率及提交时间。
     * @param orderId 普通订单在 Core 中的唯一编号
     * @param productLine 这笔业务所属的产品线，用来隔离现货、各类期货和期权
     * @param userId 这份状态所属的用户编号，必须为正数
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param side 订单的买入或卖出方向
     * @param priceTicks 委托价格，单位为最小价格 tick，具体可否为零由订单类型决定
     * @param quantitySteps 委托总数量，以币对配置的最小数量步数表示
     * @param executedQuantitySteps 已经成交的累计数量步数
     * @param remainingQuantitySteps 尚未成交的数量步数；订单撤销后也保留这部分历史数量
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
     * @param createdAtEpochMillis 这份业务记录的创建时间，使用 Unix 毫秒时间戳
     * @param updatedAtEpochMillis 最后一次更新这份记录的 Unix 毫秒时间戳
     * @param clusterPosition 这份订单提交时对应的集群日志位置，0 表示尚未写入具体提交位置
     * @param status 订单当前的有效、撤销、全部成交或拒绝状态
     * @param revision 这份状态的修订号，供结果发布和后续更新判断版本
     */
    public CoreOrderState(long orderId, ProductLine productLine, long userId, String instrumentId,
                          CoreOrderSide side, long priceTicks, long quantitySteps,
                          long executedQuantitySteps, long remainingQuantitySteps, boolean reduceOnly,
                          CoreMarginMode marginMode, CorePositionSide positionSide, CoreOrderType orderType,
                          CoreTimeInForce timeInForce, boolean postOnly, String clientOrderId, UUID commandId,
                          long makerFeeRatePpm, long takerFeeRatePpm, long createdAtEpochMillis,
                          long updatedAtEpochMillis, long clusterPosition, CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, instrumentId, side, priceTicks, priceTicks, quantitySteps,
                executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                createdAtEpochMillis, updatedAtEpochMillis, clusterPosition, status, revision);
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
     * @param remainingQuantitySteps 尚未成交的数量步数；订单撤销后也保留这部分历史数量
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
                          long executedQuantitySteps, long remainingQuantitySteps, boolean reduceOnly,
                          CoreMarginMode marginMode, CorePositionSide positionSide, CoreOrderType orderType,
                          CoreTimeInForce timeInForce, boolean postOnly, String clientOrderId, UUID commandId,
                          long makerFeeRatePpm, long takerFeeRatePpm, CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, instrumentId, side, priceTicks, priceTicks, quantitySteps,
                executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                0, 0, 0, status, revision);
    }

    /**
     * 创建分别指定委托价和撮合价、尚未附上提交信息的订单，累计费用和时间位置从 0 开始。
     * @param orderId 普通订单在 Core 中的唯一编号
     * @param productLine 这笔业务所属的产品线，用来隔离现货、各类期货和期权
     * @param userId 这份状态所属的用户编号，必须为正数
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param side 订单的买入或卖出方向
     * @param priceTicks 委托价格，单位为最小价格 tick，具体可否为零由订单类型决定
     * @param matchingPriceTicks 实际交给撮合的价格 tick，与对外保存的委托价格分别记录
     * @param quantitySteps 委托总数量，以币对配置的最小数量步数表示
     * @param executedQuantitySteps 已经成交的累计数量步数
     * @param remainingQuantitySteps 尚未成交的数量步数；订单撤销后也保留这部分历史数量
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
                          CoreOrderSide side, long priceTicks, long matchingPriceTicks,
                          long quantitySteps, long executedQuantitySteps, long remainingQuantitySteps,
                          boolean reduceOnly, CoreMarginMode marginMode, CorePositionSide positionSide,
                          CoreOrderType orderType, CoreTimeInForce timeInForce, boolean postOnly,
                          String clientOrderId, UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                          CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, instrumentId, side, priceTicks, matchingPriceTicks,
                quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                0, 0, 0, status, revision);
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
     * @param remainingQuantitySteps 尚未成交的数量步数；订单撤销后也保留这部分历史数量
     * @param reduceOnly 是否只允许减少已有持仓，不能借这笔订单增加反向或同向风险
     * @param marginMode 使用全仓还是逐仓保证金；两种模式的可用权益不能混算
     * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
     * @param status 订单当前的有效、撤销、全部成交或拒绝状态
     * @param revision 这份状态的修订号，供结果发布和后续更新判断版本
     */
    public CoreOrderState(long orderId, ProductLine productLine, long userId, String instrumentId,
                          CoreOrderSide side, long priceTicks, long quantitySteps,
                          long executedQuantitySteps, long remainingQuantitySteps, boolean reduceOnly,
                          CoreMarginMode marginMode, CorePositionSide positionSide,
                          CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, instrumentId, side, priceTicks, priceTicks, quantitySteps,
                executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false,
                "", new UUID(0, orderId), 0, 0, 0, 0, 0, status, revision);
    }

    /**
     * 有效订单转为已撤销并增加修订号，保留成交量、未成交量和历史费用；已结束的订单直接返回自身。
     */
    public CoreOrderState cancel() {
        if (status.terminal()) {
            return this;
        }
        return new CoreOrderState(orderId, productLine, userId, instrumentId,
                side, priceTicks, matchingPriceTicks, quantitySteps,
                executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                cumulativeFeeUnits, executedValueHigh, executedValueLow, createdAtEpochMillis, updatedAtEpochMillis, clusterPosition,
                CoreOrderStatus.CANCELED,
                Math.incrementExact(revision));
    }

    /**
     * 有效订单转为已拒绝并增加修订号，保留原委托和成交事实；已结束的订单直接返回自身。
     */
    public CoreOrderState reject() {
        if (status.terminal()) {
            return this;
        }
        return new CoreOrderState(orderId, productLine, userId, instrumentId,
                side, priceTicks, matchingPriceTicks, quantitySteps,
                executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                cumulativeFeeUnits, executedValueHigh, executedValueLow, createdAtEpochMillis, updatedAtEpochMillis, clusterPosition,
                CoreOrderStatus.REJECTED,
                Math.incrementExact(revision));
    }

    /**
     * 给订单增加一笔成交数量，本次费用按 0 处理，其他校验和状态推进交给带费用的方法。
     * @param quantitySteps 本次新增成交数量步数，必须为正且不超过当前剩余量
     */
    public CoreOrderState fill(long quantitySteps) {
        return fill(quantitySteps, 0);
    }

    /**
     * 仅允许有效订单在剩余量内增加成交，累计费用并增加修订号；剩余量为 0 时标为全部成交，此入口不接收成交价。
     * @param quantitySteps 本次新增成交数量步数，必须为正且不超过当前剩余量
     * @param feeUnits 这笔成交累计到订单上的费用金额，单位为结算资产最小单位
     */
    public CoreOrderState fill(long quantitySteps, long feeUnits) {
        if (status != CoreOrderStatus.OPEN || quantitySteps <= 0 || quantitySteps > remainingQuantitySteps) {
            throw new IllegalStateException("invalid order fill");
        }
        // 本次成交加上历史成交后的累计数量步数。
        long nextExecuted = Math.addExact(executedQuantitySteps, quantitySteps);
        // 扣掉本次成交后的未成交数量，归零时订单转为全部成交。
        long nextRemaining = Math.subtractExact(remainingQuantitySteps, quantitySteps);
        return new CoreOrderState(orderId, productLine, userId, instrumentId,
                side, priceTicks, matchingPriceTicks, quantitySteps(), nextExecuted, nextRemaining,
                reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly,
                clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                Math.addExact(cumulativeFeeUnits, feeUnits), createdAtEpochMillis, updatedAtEpochMillis,
                clusterPosition,
                nextRemaining == 0 ? CoreOrderStatus.FILLED : CoreOrderStatus.OPEN,
                Math.incrementExact(revision));
    }

    /**
     * 只允许有效订单修改成正的新价格，委托价和撮合价一并更新并增加修订号，不在这里重做资金预占或操作订单簿。
     * @param newPriceTicks 改单后的新委托价和撮合价，单位为价格 tick，必须为正数
     */
    public CoreOrderState replacePrice(long newPriceTicks) {
        if (status != CoreOrderStatus.OPEN || newPriceTicks <= 0) {
            throw new IllegalStateException("invalid order replace");
        }
        return new CoreOrderState(orderId, productLine, userId, instrumentId,
                side, newPriceTicks, newPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps,
                reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly,
                clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                cumulativeFeeUnits, executedValueHigh, executedValueLow, createdAtEpochMillis, updatedAtEpochMillis, clusterPosition,
                status, Math.incrementExact(revision));
    }

    /**
     * 把集群提交时间和日志位置写进新记录；首次提交补创建时间，后续提交保留原创建时间，业务修订号不变。
     * @param timestamp 本次提交采用的集群 Unix 毫秒时间戳
     * @param position 本次提交在集群日志中的位置
     */
    public CoreOrderState withCommitMetadata(long timestamp, long position) {
        return new CoreOrderState(orderId, productLine, userId, instrumentId, side, priceTicks,
                matchingPriceTicks,
                quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                cumulativeFeeUnits, executedValueHigh, executedValueLow,
                createdAtEpochMillis == 0 ? timestamp : createdAtEpochMillis,
                timestamp, position, status, revision);
    }
}
