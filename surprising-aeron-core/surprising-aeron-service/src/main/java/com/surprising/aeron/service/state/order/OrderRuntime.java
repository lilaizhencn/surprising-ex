package com.surprising.aeron.service.state.order;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.state.admission.ResolvedPlaceOrder;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.product.api.ProductLine;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个账户 Lane 持有的订单状态。Lane 可原位推进成交和终态，跨线程读取使用固定命令槽或快照；Owner 消费命令回执做有序发布，不能保留活跃权威订单当只读镜像。池化权威对象及其冻结槽要等原退休栅栏后才能复用。
 */
public final class OrderRuntime {
    /** 本订单唯一 Core 编号。 */
    private long orderId;
    /** 订单所属独立产品线。 */
    private ProductLine productLine;
    /** 订单所属用户编号。 */
    private long userId;
    /** 币对的运行时稠密编号。 */
    private int symbolId;
    /** 订单绑定的规范币对对象，业务值比较也要求同一对象身份。 */
    private CoreInstrument instrument;
    /** 买入或卖出方向。 */
    private CoreOrderSide side;
    /** 原请求的限价或现货市价保护边界，衍生品市价请求可为 0。 */
    private long priceTicks;
    /** 实际交给 Matcher 的价格或市价保护边界，与原请求价格分开保存。 */
    private long matchingPriceTicks;
    /** 订单原始总数量步数，等于已成交加剩余数量。 */
    private long quantitySteps;
    /** 已经累计成交的数量步数。 */
    private long executedQuantitySteps;
    /** 仍未成交的数量步数，撤销后也可保留未成交数量。 */
    private long remainingQuantitySteps;
    /** 是否只允许减少已有持仓。 */
    private boolean reduceOnly;
    /** 全仓或逐仓保证金模式。 */
    private CoreMarginMode marginMode;
    /** 净持仓或双向持仓腿身份。 */
    private CorePositionSide positionSide;
    /** 限价或市价等订单类型。 */
    private CoreOrderType orderType;
    /** GTC、IOC、FOK 或 GTX 等有效期策略。 */
    private CoreTimeInForce timeInForce;
    /** 是否要求只成为挂单方，构造校验要求 LIMIT 且 GTX。 */
    private boolean postOnly;
    /** 用户客户端订单标识，普通有效值不超过 64 个字符。 */
    private String clientOrderId;
    /** 创建该订单的业务命令 UUID。 */
    private UUID commandId;
    /** 下单时固定的 Maker 百万分比费率，可为负返佣。 */
    private long makerFeeRatePpm;
    /** 下单时固定的 Taker 百万分比费率。 */
    private long takerFeeRatePpm;
    /** 该订单累计手续费整数金额，包含实际收费或返佣影响。 */
    private long cumulativeFeeUnits;
    /** 累计成交值的高 64 位，简化构造入口用 -1 表示已有成交但未提供该累计值。 */
    private long executedValueHigh;
    /** 累计成交值的低 64 位，与高位共同保存原始整数位模式。 */
    private long executedValueLow;
    /** 订单创建的集群业务时间，初始为 0 时可在首次提交补齐。 */
    private long createdAtEpochMillis;
    /** 最近一次订单提交或状态更新的集群业务时间。 */
    private long updatedAtEpochMillis;
    /** 最近一次提交的集群日志位置。 */
    private long clusterPosition;
    /** 订单当前活动或终态状态。 */
    private CoreOrderStatus status;
    /** 订单业务版本，更新时由调用方提供后继版本。 */
    private long revision;
    /** 值用于可变 Lane 状态还是只读交接的标记，写入仍由调用方遵守原所有权。 */
    private boolean mutable;

    /**
     * 按完整订单元数据创建可变值但未显式提供累计成交值；无成交时该值为零，已有成交时高位用 -1 表示未提供。
     * @param orderId 本订单唯一 Core 编号
     * @param productLine 订单所属独立产品线
     * @param userId 订单所属用户编号
     * @param symbolId 币对的运行时稠密编号
     * @param instrument 订单绑定的规范币对对象，业务值比较也要求同一对象身份
     * @param side 买入或卖出方向
     * @param priceTicks 原请求的限价或现货市价保护边界，衍生品市价请求可为 0
     * @param matchingPriceTicks 实际交给 Matcher 的价格或市价保护边界，与原请求价格分开保存
     * @param quantitySteps 订单原始总数量步数，等于已成交加剩余数量
     * @param executedQuantitySteps 已经累计成交的数量步数
     * @param remainingQuantitySteps 仍未成交的数量步数，撤销后也可保留未成交数量
     * @param reduceOnly 是否只允许减少已有持仓
     * @param marginMode 全仓或逐仓保证金模式
     * @param positionSide 净持仓或双向持仓腿身份
     * @param orderType 限价或市价等订单类型
     * @param timeInForce GTC、IOC、FOK 或 GTX 等有效期策略
     * @param postOnly 是否要求只成为挂单方，构造校验要求 LIMIT 且 GTX
     * @param clientOrderId 用户客户端订单标识，普通有效值不超过 64 个字符
     * @param commandId 创建该订单的业务命令 UUID
     * @param makerFeeRatePpm 下单时固定的 Maker 百万分比费率，可为负返佣
     * @param takerFeeRatePpm 下单时固定的 Taker 百万分比费率
     * @param cumulativeFeeUnits 该订单累计手续费整数金额，包含实际收费或返佣影响
     * @param createdAtEpochMillis 订单创建的集群业务时间，初始为 0 时可在首次提交补齐
     * @param updatedAtEpochMillis 最近一次订单提交或状态更新的集群业务时间
     * @param clusterPosition 最近一次提交的集群日志位置
     * @param status 订单当前活动或终态状态
     * @param revision 订单业务版本，更新时由调用方提供后继版本
     */
    public OrderRuntime(long orderId, ProductLine productLine, long userId, int symbolId,
                        CoreInstrument instrument, CoreOrderSide side, long priceTicks,
                        long matchingPriceTicks, long quantitySteps, long executedQuantitySteps,
                        long remainingQuantitySteps, boolean reduceOnly, CoreMarginMode marginMode,
                        CorePositionSide positionSide, CoreOrderType orderType,
                        CoreTimeInForce timeInForce, boolean postOnly, String clientOrderId,
                        UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                        long cumulativeFeeUnits, long createdAtEpochMillis, long updatedAtEpochMillis,
                        long clusterPosition, CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, symbolId, instrument, side, priceTicks, matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm, cumulativeFeeUnits, executedQuantitySteps == 0 ? 0 : -1, 0, createdAtEpochMillis, updatedAtEpochMillis, clusterPosition, status, revision);
    }

    /**
     * 创建带累计费用、成交值和完整提交元数据的可变订单，核对身份、数量守恒、时间关系和基本订单类型约束。
     * @param orderId 本订单唯一 Core 编号
     * @param productLine 订单所属独立产品线
     * @param userId 订单所属用户编号
     * @param symbolId 币对的运行时稠密编号
     * @param instrument 订单绑定的规范币对对象，业务值比较也要求同一对象身份
     * @param side 买入或卖出方向
     * @param priceTicks 原请求的限价或现货市价保护边界，衍生品市价请求可为 0
     * @param matchingPriceTicks 实际交给 Matcher 的价格或市价保护边界，与原请求价格分开保存
     * @param quantitySteps 订单原始总数量步数，等于已成交加剩余数量
     * @param executedQuantitySteps 已经累计成交的数量步数
     * @param remainingQuantitySteps 仍未成交的数量步数，撤销后也可保留未成交数量
     * @param reduceOnly 是否只允许减少已有持仓
     * @param marginMode 全仓或逐仓保证金模式
     * @param positionSide 净持仓或双向持仓腿身份
     * @param orderType 限价或市价等订单类型
     * @param timeInForce GTC、IOC、FOK 或 GTX 等有效期策略
     * @param postOnly 是否要求只成为挂单方，构造校验要求 LIMIT 且 GTX
     * @param clientOrderId 用户客户端订单标识，普通有效值不超过 64 个字符
     * @param commandId 创建该订单的业务命令 UUID
     * @param makerFeeRatePpm 下单时固定的 Maker 百万分比费率，可为负返佣
     * @param takerFeeRatePpm 下单时固定的 Taker 百万分比费率
     * @param cumulativeFeeUnits 该订单累计手续费整数金额，包含实际收费或返佣影响
     * @param executedValueHigh 累计成交值的高 64 位，简化构造入口用 -1 表示已有成交但未提供该累计值
     * @param executedValueLow 累计成交值的低 64 位，与高位共同保存原始整数位模式
     * @param createdAtEpochMillis 订单创建的集群业务时间，初始为 0 时可在首次提交补齐
     * @param updatedAtEpochMillis 最近一次订单提交或状态更新的集群业务时间
     * @param clusterPosition 最近一次提交的集群日志位置
     * @param status 订单当前活动或终态状态
     * @param revision 订单业务版本，更新时由调用方提供后继版本
     */
    public OrderRuntime(long orderId, ProductLine productLine, long userId, int symbolId,
                        CoreInstrument instrument, CoreOrderSide side, long priceTicks,
                        long matchingPriceTicks, long quantitySteps, long executedQuantitySteps,
                        long remainingQuantitySteps, boolean reduceOnly, CoreMarginMode marginMode,
                        CorePositionSide positionSide, CoreOrderType orderType,
                        CoreTimeInForce timeInForce, boolean postOnly, String clientOrderId,
                        UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                        long cumulativeFeeUnits, long executedValueHigh, long executedValueLow, long createdAtEpochMillis, long updatedAtEpochMillis,
                        long clusterPosition, CoreOrderStatus status, long revision) {
        if (orderId <= 0 || productLine == null || userId <= 0 || symbolId < 0 || instrument == null
                || side == null || priceTicks < 0 || matchingPriceTicks < 0
                || quantitySteps <= 0 || executedQuantitySteps < 0 || remainingQuantitySteps < 0
                || Math.addExact(executedQuantitySteps, remainingQuantitySteps) != quantitySteps
                || marginMode == null || positionSide == null || orderType == null || timeInForce == null
                || clientOrderId == null || clientOrderId.length() > 64 || commandId == null
                || createdAtEpochMillis < 0 || updatedAtEpochMillis < createdAtEpochMillis || clusterPosition < 0
                || postOnly && (orderType != CoreOrderType.LIMIT || timeInForce != CoreTimeInForce.GTX)
                || status == null || revision <= 0
                || status == CoreOrderStatus.OPEN && remainingQuantitySteps == 0) {
            throw new IllegalArgumentException("invalid runtime order");
        }
        this.orderId = orderId;
        this.productLine = productLine;
        this.userId = userId;
        this.symbolId = symbolId;
        this.instrument = instrument;
        this.side = side;
        this.priceTicks = priceTicks;
        this.matchingPriceTicks = matchingPriceTicks;
        this.quantitySteps = quantitySteps;
        this.executedQuantitySteps = executedQuantitySteps;
        this.remainingQuantitySteps = remainingQuantitySteps;
        this.reduceOnly = reduceOnly;
        this.marginMode = marginMode;
        this.positionSide = positionSide;
        this.orderType = orderType;
        this.timeInForce = timeInForce;
        this.postOnly = postOnly;
        this.clientOrderId = clientOrderId;
        this.commandId = commandId;
        this.makerFeeRatePpm = makerFeeRatePpm;
        this.takerFeeRatePpm = takerFeeRatePpm;
        this.cumulativeFeeUnits = cumulativeFeeUnits;
        this.executedValueHigh = executedValueHigh;
        this.executedValueLow = executedValueLow;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.updatedAtEpochMillis = updatedAtEpochMillis;
        this.clusterPosition = clusterPosition;
        this.status = status;
        this.revision = revision;
        this.mutable = true;
    }

    /**
     * 先通过完整构造校验业务字段，再设置交接用途标记，快照路径用它标记非可变值。
     * @param orderId 本订单唯一 Core 编号
     * @param productLine 订单所属独立产品线
     * @param userId 订单所属用户编号
     * @param symbolId 币对的运行时稠密编号
     * @param instrument 订单绑定的规范币对对象，业务值比较也要求同一对象身份
     * @param side 买入或卖出方向
     * @param priceTicks 原请求的限价或现货市价保护边界，衍生品市价请求可为 0
     * @param matchingPriceTicks 实际交给 Matcher 的价格或市价保护边界，与原请求价格分开保存
     * @param quantitySteps 订单原始总数量步数，等于已成交加剩余数量
     * @param executedQuantitySteps 已经累计成交的数量步数
     * @param remainingQuantitySteps 仍未成交的数量步数，撤销后也可保留未成交数量
     * @param reduceOnly 是否只允许减少已有持仓
     * @param marginMode 全仓或逐仓保证金模式
     * @param positionSide 净持仓或双向持仓腿身份
     * @param orderType 限价或市价等订单类型
     * @param timeInForce GTC、IOC、FOK 或 GTX 等有效期策略
     * @param postOnly 是否要求只成为挂单方，构造校验要求 LIMIT 且 GTX
     * @param clientOrderId 用户客户端订单标识，普通有效值不超过 64 个字符
     * @param commandId 创建该订单的业务命令 UUID
     * @param makerFeeRatePpm 下单时固定的 Maker 百万分比费率，可为负返佣
     * @param takerFeeRatePpm 下单时固定的 Taker 百万分比费率
     * @param cumulativeFeeUnits 该订单累计手续费整数金额，包含实际收费或返佣影响
     * @param executedValueHigh 累计成交值的高 64 位，简化构造入口用 -1 表示已有成交但未提供该累计值
     * @param executedValueLow 累计成交值的低 64 位，与高位共同保存原始整数位模式
     * @param createdAtEpochMillis 订单创建的集群业务时间，初始为 0 时可在首次提交补齐
     * @param updatedAtEpochMillis 最近一次订单提交或状态更新的集群业务时间
     * @param clusterPosition 最近一次提交的集群日志位置
     * @param status 订单当前活动或终态状态
     * @param revision 订单业务版本，更新时由调用方提供后继版本
     * @param mutable 值用于可变 Lane 状态还是只读交接的标记，写入仍由调用方遵守原所有权
     */
    private OrderRuntime(long orderId, ProductLine productLine, long userId, int symbolId,
                         CoreInstrument instrument, CoreOrderSide side, long priceTicks,
                         long matchingPriceTicks, long quantitySteps, long executedQuantitySteps,
                         long remainingQuantitySteps, boolean reduceOnly, CoreMarginMode marginMode,
                         CorePositionSide positionSide, CoreOrderType orderType,
                         CoreTimeInForce timeInForce, boolean postOnly, String clientOrderId,
                         UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                         long cumulativeFeeUnits, long executedValueHigh, long executedValueLow, long createdAtEpochMillis, long updatedAtEpochMillis,
                         long clusterPosition, CoreOrderStatus status, long revision, boolean mutable) {
        this(orderId, productLine, userId, symbolId, instrument, side, priceTicks,
                matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps,
                reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId,
                commandId, makerFeeRatePpm, takerFeeRatePpm, cumulativeFeeUnits, executedValueHigh, executedValueLow, createdAtEpochMillis,
                updatedAtEpochMillis, clusterPosition, status, revision);
        // 委托构造先填充业务字段并默认作为 Lane 可变值；快照路径再传 false 标记发布用途，不把它当额外业务状态。
        this.mutable = mutable;
    }

    /**
     * 只提供一个价格时把原价格与实际撮合价设为相同，累计手续费从零开始。
     * @param orderId 本订单唯一 Core 编号
     * @param productLine 订单所属独立产品线
     * @param userId 订单所属用户编号
     * @param symbolId 币对的运行时稠密编号
     * @param instrument 订单绑定的规范币对对象，业务值比较也要求同一对象身份
     * @param side 买入或卖出方向
     * @param priceTicks 原请求的限价或现货市价保护边界，衍生品市价请求可为 0
     * @param quantitySteps 订单原始总数量步数，等于已成交加剩余数量
     * @param executedQuantitySteps 已经累计成交的数量步数
     * @param remainingQuantitySteps 仍未成交的数量步数，撤销后也可保留未成交数量
     * @param reduceOnly 是否只允许减少已有持仓
     * @param marginMode 全仓或逐仓保证金模式
     * @param positionSide 净持仓或双向持仓腿身份
     * @param orderType 限价或市价等订单类型
     * @param timeInForce GTC、IOC、FOK 或 GTX 等有效期策略
     * @param postOnly 是否要求只成为挂单方，构造校验要求 LIMIT 且 GTX
     * @param clientOrderId 用户客户端订单标识，普通有效值不超过 64 个字符
     * @param commandId 创建该订单的业务命令 UUID
     * @param makerFeeRatePpm 下单时固定的 Maker 百万分比费率，可为负返佣
     * @param takerFeeRatePpm 下单时固定的 Taker 百万分比费率
     * @param createdAtEpochMillis 订单创建的集群业务时间，初始为 0 时可在首次提交补齐
     * @param updatedAtEpochMillis 最近一次订单提交或状态更新的集群业务时间
     * @param clusterPosition 最近一次提交的集群日志位置
     * @param status 订单当前活动或终态状态
     * @param revision 订单业务版本，更新时由调用方提供后继版本
     */
    public OrderRuntime(long orderId, ProductLine productLine, long userId, int symbolId,
                        CoreInstrument instrument, CoreOrderSide side, long priceTicks,
                        long quantitySteps, long executedQuantitySteps, long remainingQuantitySteps,
                        boolean reduceOnly, CoreMarginMode marginMode, CorePositionSide positionSide,
                        CoreOrderType orderType, CoreTimeInForce timeInForce, boolean postOnly,
                        String clientOrderId, UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                        long createdAtEpochMillis, long updatedAtEpochMillis, long clusterPosition,
                        CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, symbolId, instrument, side, priceTicks, priceTicks,
                quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode,
                positionSide, orderType, timeInForce, postOnly, clientOrderId, commandId,
                makerFeeRatePpm, takerFeeRatePpm, 0, createdAtEpochMillis, updatedAtEpochMillis,
                clusterPosition, status, revision);
    }

    /**
     * 显式区分原请求价和撮合边界，但不提供累计费用，初始费用为零。
     * @param orderId 本订单唯一 Core 编号
     * @param productLine 订单所属独立产品线
     * @param userId 订单所属用户编号
     * @param symbolId 币对的运行时稠密编号
     * @param instrument 订单绑定的规范币对对象，业务值比较也要求同一对象身份
     * @param side 买入或卖出方向
     * @param priceTicks 原请求的限价或现货市价保护边界，衍生品市价请求可为 0
     * @param matchingPriceTicks 实际交给 Matcher 的价格或市价保护边界，与原请求价格分开保存
     * @param quantitySteps 订单原始总数量步数，等于已成交加剩余数量
     * @param executedQuantitySteps 已经累计成交的数量步数
     * @param remainingQuantitySteps 仍未成交的数量步数，撤销后也可保留未成交数量
     * @param reduceOnly 是否只允许减少已有持仓
     * @param marginMode 全仓或逐仓保证金模式
     * @param positionSide 净持仓或双向持仓腿身份
     * @param orderType 限价或市价等订单类型
     * @param timeInForce GTC、IOC、FOK 或 GTX 等有效期策略
     * @param postOnly 是否要求只成为挂单方，构造校验要求 LIMIT 且 GTX
     * @param clientOrderId 用户客户端订单标识，普通有效值不超过 64 个字符
     * @param commandId 创建该订单的业务命令 UUID
     * @param makerFeeRatePpm 下单时固定的 Maker 百万分比费率，可为负返佣
     * @param takerFeeRatePpm 下单时固定的 Taker 百万分比费率
     * @param createdAtEpochMillis 订单创建的集群业务时间，初始为 0 时可在首次提交补齐
     * @param updatedAtEpochMillis 最近一次订单提交或状态更新的集群业务时间
     * @param clusterPosition 最近一次提交的集群日志位置
     * @param status 订单当前活动或终态状态
     * @param revision 订单业务版本，更新时由调用方提供后继版本
     */
    public OrderRuntime(long orderId, ProductLine productLine, long userId, int symbolId,
                        CoreInstrument instrument, CoreOrderSide side, long priceTicks,
                        long matchingPriceTicks, long quantitySteps, long executedQuantitySteps,
                        long remainingQuantitySteps, boolean reduceOnly, CoreMarginMode marginMode,
                        CorePositionSide positionSide, CoreOrderType orderType, CoreTimeInForce timeInForce,
                        boolean postOnly, String clientOrderId, UUID commandId, long makerFeeRatePpm,
                        long takerFeeRatePpm, long createdAtEpochMillis, long updatedAtEpochMillis,
                        long clusterPosition, CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, symbolId, instrument, side, priceTicks,
                matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps,
                reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId,
                commandId, makerFeeRatePpm, takerFeeRatePpm, 0, createdAtEpochMillis,
                updatedAtEpochMillis, clusterPosition, status, revision);
    }

    /**
     * 简化构造入口默认采用 U 本位永续产品线，再按给定成交量和取消标记创建订单。
     * @param orderId 本订单唯一 Core 编号
     * @param userId 订单所属用户编号
     * @param symbolId 币对的运行时稠密编号
     * @param instrument 订单绑定的规范币对对象，业务值比较也要求同一对象身份
     * @param side 买入或卖出方向
     * @param priceTicks 原请求的限价或现货市价保护边界，衍生品市价请求可为 0
     * @param reduceOnly 是否只允许减少已有持仓
     * @param marginMode 全仓或逐仓保证金模式
     * @param positionSide 净持仓或双向持仓腿身份
     * @param orderType 限价或市价等订单类型
     * @param timeInForce GTC、IOC、FOK 或 GTX 等有效期策略
     * @param makerFeeRatePpm 下单时固定的 Maker 百万分比费率，可为负返佣
     * @param takerFeeRatePpm 下单时固定的 Taker 百万分比费率
     * @param quantitySteps 订单原始总数量步数，等于已成交加剩余数量
     * @param executedQuantitySteps 已经累计成交的数量步数
     * @param remainingQuantitySteps 仍未成交的数量步数，撤销后也可保留未成交数量
     * @param canceled 简化构造入口的取消标记，true 创建 CANCELED，false 创建 OPEN
     */
    public OrderRuntime(long orderId, long userId, int symbolId, CoreInstrument instrument,
                        CoreOrderSide side, long priceTicks, boolean reduceOnly,
                        CoreMarginMode marginMode, CorePositionSide positionSide,
                        CoreOrderType orderType, CoreTimeInForce timeInForce,
                        long makerFeeRatePpm, long takerFeeRatePpm, long quantitySteps,
                        long executedQuantitySteps, long remainingQuantitySteps, boolean canceled) {
        this(orderId, ProductLine.LINEAR_PERPETUAL, userId, symbolId, instrument, side,
                priceTicks, reduceOnly, marginMode, positionSide, orderType, timeInForce,
                makerFeeRatePpm, takerFeeRatePpm, quantitySteps, executedQuantitySteps,
                remainingQuantitySteps, canceled);
    }

    /**
     * 按指定产品线创建简化订单，客户端标识为空、命令 UUID 由订单编号构造、提交时间位置为零，取消标记决定 CANCELED 或 OPEN。
     * @param orderId 本订单唯一 Core 编号
     * @param productLine 订单所属独立产品线
     * @param userId 订单所属用户编号
     * @param symbolId 币对的运行时稠密编号
     * @param instrument 订单绑定的规范币对对象，业务值比较也要求同一对象身份
     * @param side 买入或卖出方向
     * @param priceTicks 原请求的限价或现货市价保护边界，衍生品市价请求可为 0
     * @param reduceOnly 是否只允许减少已有持仓
     * @param marginMode 全仓或逐仓保证金模式
     * @param positionSide 净持仓或双向持仓腿身份
     * @param orderType 限价或市价等订单类型
     * @param timeInForce GTC、IOC、FOK 或 GTX 等有效期策略
     * @param makerFeeRatePpm 下单时固定的 Maker 百万分比费率，可为负返佣
     * @param takerFeeRatePpm 下单时固定的 Taker 百万分比费率
     * @param quantitySteps 订单原始总数量步数，等于已成交加剩余数量
     * @param executedQuantitySteps 已经累计成交的数量步数
     * @param remainingQuantitySteps 仍未成交的数量步数，撤销后也可保留未成交数量
     * @param canceled 简化构造入口的取消标记，true 创建 CANCELED，false 创建 OPEN
     */
    public OrderRuntime(long orderId, ProductLine productLine, long userId, int symbolId,
                        CoreInstrument instrument, CoreOrderSide side, long priceTicks,
                        boolean reduceOnly, CoreMarginMode marginMode, CorePositionSide positionSide,
                        CoreOrderType orderType, CoreTimeInForce timeInForce, long makerFeeRatePpm,
                        long takerFeeRatePpm, long quantitySteps, long executedQuantitySteps,
                        long remainingQuantitySteps, boolean canceled) {
        this(orderId, productLine, userId, symbolId, instrument, side, priceTicks, priceTicks,
                quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode,
                positionSide, orderType, timeInForce, false, "", new UUID(0, orderId),
                makerFeeRatePpm, takerFeeRatePpm, 0, 0, 0, 0,
                canceled ? CoreOrderStatus.CANCELED : CoreOrderStatus.OPEN, 1);
    }

    /**
     * 返回本订单唯一 Core 编号。
     */
    public long orderId() { return orderId; }
    /**
     * 返回订单所属独立产品线。
     */
    public ProductLine productLine() { return productLine; }
    /**
     * 返回订单所属用户编号。
     */
    public long userId() { return userId; }
    /**
     * 返回币对的运行时稠密编号。
     */
    public int symbolId() { return symbolId; }
    /**
     * 返回订单绑定的规范币对对象，业务值比较也要求同一对象身份。
     */
    public CoreInstrument instrument() { return instrument; }
    /**
     * 返回买入或卖出方向。
     */
    public CoreOrderSide side() { return side; }
    /**
     * 返回原请求的限价或现货市价保护边界，衍生品市价请求可为 0。
     */
    public long priceTicks() { return priceTicks; }
    /**
     * 返回实际交给 Matcher 的价格或市价保护边界，与原请求价格分开保存。
     */
    public long matchingPriceTicks() { return matchingPriceTicks; }
    /**
     * 返回订单原始总数量步数，等于已成交加剩余数量。
     */
    public long quantitySteps() { return quantitySteps; }
    /**
     * 返回已经累计成交的数量步数。
     */
    public long executedQuantitySteps() { return executedQuantitySteps; }
    /**
     * 返回仍未成交的数量步数，撤销后也可保留未成交数量。
     */
    public long remainingQuantitySteps() { return remainingQuantitySteps; }
    /**
     * 返回是否只允许减少已有持仓。
     */
    public boolean reduceOnly() { return reduceOnly; }
    /**
     * 返回全仓或逐仓保证金模式。
     */
    public CoreMarginMode marginMode() { return marginMode; }
    /**
     * 返回净持仓或双向持仓腿身份。
     */
    public CorePositionSide positionSide() { return positionSide; }
    /**
     * 返回限价或市价等订单类型。
     */
    public CoreOrderType orderType() { return orderType; }
    /**
     * 返回GTC、IOC、FOK 或 GTX 等有效期策略。
     */
    public CoreTimeInForce timeInForce() { return timeInForce; }
    /**
     * 返回是否要求只成为挂单方，构造校验要求 LIMIT 且 GTX。
     */
    public boolean postOnly() { return postOnly; }
    /**
     * 返回用户客户端订单标识，普通有效值不超过 64 个字符。
     */
    public String clientOrderId() { return clientOrderId; }
    /**
     * 返回创建该订单的业务命令 UUID。
     */
    public UUID commandId() { return commandId; }
    /**
     * 返回下单时固定的 Maker 百万分比费率，可为负返佣。
     */
    public long makerFeeRatePpm() { return makerFeeRatePpm; }
    /**
     * 返回下单时固定的 Taker 百万分比费率。
     */
    public long takerFeeRatePpm() { return takerFeeRatePpm; }
    /**
     * 返回该订单累计手续费整数金额，包含实际收费或返佣影响。
     */
    public long cumulativeFeeUnits() { return cumulativeFeeUnits; }
    /**
     * 返回累计成交值的高 64 位，简化构造入口用 -1 表示已有成交但未提供该累计值。
     */
    public long executedValueHigh() { return executedValueHigh; }
    /**
     * 返回累计成交值的低 64 位，与高位共同保存原始整数位模式。
     */
    public long executedValueLow() { return executedValueLow; }
    /**
     * 返回订单创建的集群业务时间，初始为 0 时可在首次提交补齐。
     */
    public long createdAtEpochMillis() { return createdAtEpochMillis; }
    /**
     * 返回最近一次订单提交或状态更新的集群业务时间。
     */
    public long updatedAtEpochMillis() { return updatedAtEpochMillis; }
    /**
     * 返回最近一次提交的集群日志位置。
     */
    public long clusterPosition() { return clusterPosition; }
    /**
     * 返回订单当前活动或终态状态。
     */
    public CoreOrderStatus status() { return status; }
    /**
     * 返回订单业务版本，更新时由调用方提供后继版本。
     */
    public long revision() { return revision; }

    /**
     * 名称沿用 canceled，但实际返回 status.terminal：已成交、已拒绝或其他终态也会返回 true。
     */
    public boolean canceled() { return status.terminal(); }

    /**
     * 复制当前全部业务值并标记为只读交接用途，供明确的发布、快照或回滚边界使用，不随原 Lane 后续成交改变。
     */
    public OrderRuntime snapshot() {
        return new OrderRuntime(orderId, productLine, userId, symbolId, instrument, side,
                priceTicks, matchingPriceTicks, quantitySteps, executedQuantitySteps,
                remainingQuantitySteps, reduceOnly, marginMode, positionSide, orderType, timeInForce,
                postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                cumulativeFeeUnits, executedValueHigh, executedValueLow, createdAtEpochMillis, updatedAtEpochMillis, clusterPosition,
                status, revision, false);
    }

    /**
     * 复制业务值到调用方持有的固定命令槽并标为非可变；目标只能是命令槽，必须等原消费者和响应退休后才能覆盖。
     * @param target 调用方拥有并已允许覆盖的命令槽或权威池对象，具体入口分别校验，工厂允许 null 时创建
     */
    public OrderRuntime copyIntoCommandSlot(OrderRuntime target) {
        if (target == null) {
            target = new OrderRuntime();
            target.commandSlotValue = true;
        }
        if (!target.commandSlotValue) throw new IllegalArgumentException("order copy target is not a command slot");
        copyStateTo(target);
        target.mutable = false;
        return target;
    }

    /**
     * 要求 Matcher 读取的是非可变交接值，用户和订单身份正确且仍有正剩余量及撮合价格，不能把活动 Lane 权威对象直接交给 Matcher。
     * @param userId 订单所属用户编号
     */
    public void requireFrozenMatcherInput(long userId) {
        if (mutable || this.userId != userId || orderId <= 0 || instrument == null
                || remainingQuantitySteps <= 0 || matchingPriceTicks <= 0)
            throw new IllegalArgumentException("matcher requires a frozen order with remaining quantity for this user");
    }

    /**
     * 将同订单编号的普通冻结前值恢复进原可变 Lane 对象，禁止用命令槽作为恢复目标或前值；保留目标原有池归属及常驻冻结租约。
     * @param before 同一订单的普通冻结前值，不能是可变值或命令槽值
     */
    public void restoreCapturedValue(OrderRuntime before) {
        if (!mutable || commandSlotValue || before == null || before.mutable || before.commandSlotValue
                || orderId != before.orderId)
            throw new IllegalStateException("invalid authoritative order restoration");
        before.copyStateTo(this);
    }

    /**
     * 逐字段复制订单业务状态和派生持仓键，不复制池归属、退休或常驻冻结槽等生命周期设施。
     * @param target 调用方拥有并已允许覆盖的命令槽或权威池对象，具体入口分别校验，工厂允许 null 时创建
     */
    private void copyStateTo(OrderRuntime target) {
        target.orderId = orderId;
        target.productLine = productLine;
        target.userId = userId;
        target.symbolId = symbolId;
        target.instrument = instrument;
        target.side = side;
        target.priceTicks = priceTicks;
        target.matchingPriceTicks = matchingPriceTicks;
        target.quantitySteps = quantitySteps;
        target.reduceOnly = reduceOnly;
        target.marginMode = marginMode;
        target.positionSide = positionSide;
        target.orderType = orderType;
        target.timeInForce = timeInForce;
        target.postOnly = postOnly;
        target.clientOrderId = clientOrderId;
        target.commandId = commandId;
        target.makerFeeRatePpm = makerFeeRatePpm;
        target.takerFeeRatePpm = takerFeeRatePpm;
        target.executedQuantitySteps = executedQuantitySteps;
        target.remainingQuantitySteps = remainingQuantitySteps;
        target.cumulativeFeeUnits = cumulativeFeeUnits;
        target.executedValueHigh = executedValueHigh;
        target.executedValueLow = executedValueLow;
        target.createdAtEpochMillis = createdAtEpochMillis;
        target.updatedAtEpochMillis = updatedAtEpochMillis;
        target.clusterPosition = clusterPosition;
        target.status = status;
        target.revision = revision;
        target.cachedPositionKey = cachedPositionKey;
    }

    /**
     * 只清理命令槽的请求引用和关键身份，存储保留给下一条命令；不能拿此入口清理权威订单。
     */
    public void clearCommandSlot() {
        if (!commandSlotValue) throw new IllegalStateException("cannot clear an authoritative order as a command slot");
        productLine = null; instrument = null; side = null; marginMode = null; positionSide = null;
        orderType = null; timeInForce = null; clientOrderId = null; commandId = null; status = null;
        orderId = userId = revision = cachedPositionKey = 0;
    }

    /** 是否为命令固定槽，生命周期跟随原响应及命令退休，不能当 Lane 权威槽清理。 */
    private boolean commandSlotValue;
    /**
     * 只为原对象池和命令槽工厂分配空存储，调用方随后必须完整初始化再作为订单使用。
     */
    private OrderRuntime() { }

    /** 是否为账户 Lane 对象池拥有的权威订单，区别于普通值和命令槽。 */
    private boolean pooledAuthority;
    /** 权威池订单登记的退休命令序号，0 表示尚未登记退休。 */
    private long retiredAfterSequence;
    /** 由用户、永久币对和持仓腿派生的持仓键缓存，新订单复用前清零。 */
    private long cachedPositionKey;
    /** 随池化权威订单一起跨越退休栅栏的常驻冻结槽，不复制到命令快照。 */
    private ReservationRuntime reservationSlot;
    /** 本次权威订单是否已建立冻结，防止同一订单重复准备常驻槽。 */
    private boolean reservationPrepared;

    /**
     * 懒计算用户、永久币对及持仓腿的派生键，并缓存到当前订单，不分配新的权威持仓身份。
     */
    public long positionIdentityKey() {
        // 当前订单已缓存或刚派生的持仓身份键，0 表示需要首次计算。
        long key = cachedPositionKey;
        if (key == 0) {
            key = RuntimeIdentityRegistry.positionIdentityKey(userId, instrument.instrumentId(), positionSide);
            cachedPositionKey = key;
        }
        return key;
    }

    /**
     * 返回本对象是否属于原命令固定槽，供复制和复用边界核对。
     */
    public boolean commandSlotValue() { return commandSlotValue; }

    /**
     * 首次创建或复用原命令槽，写入获准订单的身份、参数和初始状态，再标为只读撮合输入。
     * @param target 调用方拥有并已允许覆盖的命令槽或权威池对象，具体入口分别校验，工厂允许 null 时创建
     * @param product 本次准入所归属的独立产品线
     * @param userId 订单所属用户编号
     * @param intent 已经解析价格、资产和费率的获准下单输入
     * @param commandId 创建该订单的业务命令 UUID
     * @param symbolId 币对的运行时稠密编号
     * @param timestamp 当前提交或创建订单采用的集群业务毫秒时间
     * @param position 当前命令的集群日志位置
     */
    public static OrderRuntime prepareCommandOrder(OrderRuntime target, ProductLine product, long userId,
            ResolvedPlaceOrder intent, UUID commandId, int symbolId, long timestamp, long position) {
        if (target == null) { target = new OrderRuntime(); target.commandSlotValue = true; }
        if (!target.commandSlotValue) throw new IllegalStateException("order is not a command slot");
        initializeAdmission(target, product, userId, intent, commandId, symbolId, timestamp, position);
        target.mutable = false;
        return target;
    }

    /**
     * 首次创建或复用 Lane 权威池对象，要求目标类型正确并初始化本单；调用方负责先满足原订单退休栅栏。
     * @param target 调用方拥有并已允许覆盖的命令槽或权威池对象，具体入口分别校验，工厂允许 null 时创建
     * @param product 本次准入所归属的独立产品线
     * @param userId 订单所属用户编号
     * @param intent 已经解析价格、资产和费率的获准下单输入
     * @param commandId 创建该订单的业务命令 UUID
     * @param symbolId 币对的运行时稠密编号
     * @param timestamp 当前提交或创建订单采用的集群业务毫秒时间
     * @param position 当前命令的集群日志位置
     */
    public static OrderRuntime preparePooled(OrderRuntime target, ProductLine product, long userId,
            ResolvedPlaceOrder intent, UUID commandId, int symbolId, long timestamp, long position) {
        if (intent == null || commandId == null || userId <= 0 || symbolId < 0 || timestamp < 0 || position < 0)
            throw new IllegalArgumentException("invalid pooled order admission");
        if (target == null) { target = new OrderRuntime(); target.pooledAuthority = true; }
        if (!target.pooledAuthority || target.commandSlotValue) throw new IllegalStateException("order is not a Lane pool value");
        initializeAdmission(target, product, userId, intent, commandId, symbolId, timestamp, position);
        return target;
    }

    /**
     * 校验基本入口参数后绑定新单身份和决策，数量全部置为剩余量、成交和费用归零，版本从 1 开始，并清理前单退休与冻结准备标记。
     * @param target 调用方拥有并已允许覆盖的命令槽或权威池对象，具体入口分别校验，工厂允许 null 时创建
     * @param product 本次准入所归属的独立产品线
     * @param userId 订单所属用户编号
     * @param intent 已经解析价格、资产和费率的获准下单输入
     * @param commandId 创建该订单的业务命令 UUID
     * @param symbolId 币对的运行时稠密编号
     * @param timestamp 当前提交或创建订单采用的集群业务毫秒时间
     * @param position 当前命令的集群日志位置
     */
    private static void initializeAdmission(OrderRuntime target, ProductLine product, long userId,
            ResolvedPlaceOrder intent, UUID commandId, int symbolId, long timestamp, long position) {
        if (intent == null || commandId == null || product == null || userId <= 0 || symbolId < 0 || timestamp < 0 || position < 0)
            throw new IllegalArgumentException("invalid order admission");
        target.orderId = intent.orderId(); target.productLine = product; target.userId = userId;
        target.symbolId = symbolId; target.instrument = intent.instrument(); target.side = intent.side();
        target.priceTicks = intent.limitPriceTicks(); target.matchingPriceTicks = intent.matchingPriceTicks();
        target.quantitySteps = target.remainingQuantitySteps = intent.quantitySteps();
        target.executedQuantitySteps = target.cumulativeFeeUnits = target.executedValueHigh = target.executedValueLow = 0;
        target.reduceOnly = intent.reduceOnly(); target.marginMode = intent.marginMode(); target.positionSide = intent.positionSide();
        target.orderType = intent.orderType(); target.timeInForce = intent.timeInForce(); target.postOnly = intent.postOnly();
        target.clientOrderId = intent.clientOrderId(); target.commandId = commandId;
        target.makerFeeRatePpm = intent.makerFeeRatePpm(); target.takerFeeRatePpm = intent.takerFeeRatePpm();
        target.createdAtEpochMillis = target.updatedAtEpochMillis = timestamp; target.clusterPosition = position;
        target.status = CoreOrderStatus.OPEN; target.revision = 1; target.mutable = true; target.retiredAfterSequence = 0;
        target.cachedPositionKey = 0;
        target.reservationPrepared = false;
    }

    /**
     * 普通订单创建独立冻结；池化权威订单复用自己的常驻冻结槽，同一单只能准备一次，已登记退休的订单不能再准备。
     * @param kind 所需冻结的现货资产或衍生品保证金类别
     * @param assetId 冻结资产的运行时编号
     * @param reservedUnits 本订单需要冻结的正整数总额
     */
    public ReservationRuntime prepareReservation(com.surprising.aeron.protocol.ReservationKind kind,
                                           int assetId, long reservedUnits) {
        if (!pooledAuthority) return new ReservationRuntime(orderId, userId, symbolId, kind,
                assetId, reservedUnits, 0, 0, quantitySteps);
        if (retiredAfterSequence != 0 || reservationPrepared)
            throw new IllegalStateException("reservation is already prepared or its order is retired");
        reservationSlot = ReservationRuntime.prepareForOrderSlot(reservationSlot, orderId, userId,
                symbolId, kind, assetId, reservedUnits, quantitySteps);
        reservationPrepared = true;
        return reservationSlot;
    }

    /**
     * 非池对象返回 false，权威池对象登记一次正退休序号；此处只登记，实际再次使用仍由原提交水位栅栏控制。
     * @param sequence 权威订单最后使用的正数 Core 命令序号，作为退休边界
     */
    public boolean retireForReuse(long sequence) {
        if (!pooledAuthority) return false;
        if (sequence <= 0 || retiredAfterSequence != 0) throw new IllegalStateException("invalid order retirement");
        retiredAfterSequence = sequence;
        return true;
    }

    /**
     * 返回已登记退休序号，0 表示本单尚未登记退休。
     */
    public long retiredAfterSequence() { return retiredAfterSequence; }

    /**
     * 要求权威池对象已经登记退休，释放客户标识、命令和币对等引用并清派生键；数值存储留给下一次完整初始化。
     */
    public void clearRetiredReferences() {
        if (!pooledAuthority || retiredAfterSequence == 0) throw new IllegalStateException("order was not retired");
        clientOrderId = null; commandId = null; instrument = null; productLine = null;
        cachedPositionKey = 0;
    }

    /**
     * 已是可变值则直接返回，否则复制成普通可变 Lane 值，避免把发布对象直接当活动状态接管。
     */
    public OrderRuntime laneValue() { return mutable ? this : mutableCopy(); }

    /**
     * 始终创建新的普通可变值供 Lane 接管，不继承原对象的命令槽或池标记。
     */
    OrderRuntime copyForLane() { return mutableCopy(); }

    /**
     * 完整复制当前订单业务字段为新可变值，池归属、常驻冻结和退休设施不随之复制。
     */
    private OrderRuntime mutableCopy() {
        return new OrderRuntime(orderId, productLine, userId, symbolId, instrument, side,
                priceTicks, matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps,
                reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId, commandId,
                makerFeeRatePpm, takerFeeRatePpm, cumulativeFeeUnits, executedValueHigh, executedValueLow, createdAtEpochMillis, updatedAtEpochMillis,
                clusterPosition, status, revision);
    }

    /**
     * 活动可变值先捕获快照，已有非可变交接值直接返回，沿用原发布租约。
     */
    public OrderRuntime publicationValue() { return mutable ? snapshot() : this; }

    /**
     * 核对订单编号、规范币对对象、数量守恒和基本提交元数据，再原位采用已发布的成交量、费用、累计值、时间、状态和版本。
     * @param source 同一订单及规范币对身份的发布来源，用于核对原位更新目标
     * @param executed 更新后的累计已成交数量步数
     * @param remaining 更新后的剩余数量步数，两者精确和必须等于原始数量
     * @param cumulativeFee 发布事实中已经累计好的手续费总额
     * @param valueHigh 发布事实中累计成交值的高 64 位
     * @param valueLow 发布事实中累计成交值的低 64 位
     * @param createdAt 发布事实中的订单创建时间
     * @param updatedAt 发布事实中的最近更新时间
     * @param position 当前命令的集群日志位置
     * @param publishedStatus 发布事实中的订单业务状态
     * @param publishedRevision 发布事实中的正数订单版本
     */
    void applyPublishedStateInPlace(OrderRuntime source, long executed, long remaining,
                                    long cumulativeFee, long valueHigh, long valueLow, long createdAt, long updatedAt,
                                    long position, CoreOrderStatus publishedStatus,
                                    long publishedRevision) {
        if (source == null || orderId != source.orderId || instrument != source.instrument
                || executed < 0 || remaining < 0 || Math.addExact(executed, remaining) != quantitySteps
                || createdAt < 0 || updatedAt < createdAt || position < 0
                || publishedStatus == null || publishedRevision <= 0) {
            throw new IllegalStateException("invalid published order state");
        }
        executedQuantitySteps = executed;
        remainingQuantitySteps = remaining;
        cumulativeFeeUnits = cumulativeFee;
        executedValueHigh = valueHigh;
        executedValueLow = valueLow;
        createdAtEpochMillis = createdAt;
        updatedAtEpochMillis = updatedAt;
        clusterPosition = position;
        status = publishedStatus;
        revision = publishedRevision;
    }

    /**
     * 由所属 Lane 校验数量及基本提交参数后写入成交和剩余量，再精确累加手续费并推进状态版本；非负提交时间会同时补齐创建时间和更新日志位置，异常由原命令回滚边界处理。
     * @param executed 更新后的累计已成交数量步数
     * @param remaining 更新后的剩余数量步数，两者精确和必须等于原始数量
     * @param feeUnits 本次新增加的手续费整数金额，负数可表示返佣
     * @param nextStatus 本次推进到的订单状态
     * @param nextRevision 本次推进到的正数订单版本
     * @param commitTimestamp 本次集群提交时间；不更新元数据的调用使用 -1
     * @param commitPosition 本次集群日志位置；不更新元数据的调用使用 -1
     */
    public void applyFillInPlace(long executed, long remaining, long feeUnits,
                          CoreOrderStatus nextStatus, long nextRevision,
                          long commitTimestamp, long commitPosition) {
        if (executed < 0 || remaining < 0 || Math.addExact(executed, remaining) != quantitySteps
                || nextStatus == null || nextRevision <= 0 || commitTimestamp < -1 || commitPosition < -1
                || commitTimestamp >= 0 && commitPosition < 0) {
            throw new IllegalArgumentException("invalid runtime order execution");
        }
        executedQuantitySteps = executed;
        remainingQuantitySteps = remaining;
        cumulativeFeeUnits = Math.addExact(cumulativeFeeUnits, feeUnits);
        if (commitTimestamp >= 0) {
            if (createdAtEpochMillis == 0) createdAtEpochMillis = commitTimestamp;
            updatedAtEpochMillis = commitTimestamp;
            clusterPosition = commitPosition;
        }
        status = nextStatus;
        revision = nextRevision;
    }

    /**
     * 保留当前成交与剩余数量，按零新增费用调用原位成交状态入口，只推进状态、版本及可选提交元数据。
     * @param nextStatus 本次推进到的订单状态
     * @param nextRevision 本次推进到的正数订单版本
     * @param commitTimestamp 本次集群提交时间；不更新元数据的调用使用 -1
     * @param commitPosition 本次集群日志位置；不更新元数据的调用使用 -1
     */
    public void applyStatusInPlace(CoreOrderStatus nextStatus, long nextRevision,
                            long commitTimestamp, long commitPosition) {
        applyFillInPlace(executedQuantitySteps, remainingQuantitySteps, 0,
                nextStatus, nextRevision, commitTimestamp, commitPosition);
    }

    /**
     * 原位写入非负集群时间和日志位置，创建时间原为 0 时只补齐一次。
     * @param timestamp 当前提交或创建订单采用的集群业务毫秒时间
     * @param position 当前命令的集群日志位置
     */
    public void applyCommitMetadataInPlace(long timestamp, long position) {
        if (timestamp < 0 || position < 0) throw new IllegalArgumentException("invalid runtime order metadata");
        if (createdAtEpochMillis == 0) createdAtEpochMillis = timestamp;
        updatedAtEpochMillis = timestamp;
        clusterPosition = position;
    }

    /**
     * 原位写入累计成交值的高低位，高位小于 -1 时拒绝，保留 -1 的未提供哨兵。
     * @param high 累计成交值高 64 位，-1 表示未提供，不能小于 -1
     * @param low 累计成交值低 64 位，保留原始位模式
     */
    public void executionValue(long high, long low) {
        if (high < -1) throw new IllegalArgumentException("invalid execution value");
        executedValueHigh = high;
        executedValueLow = low;
    }

    /**
     * 复制订单后只修改累计成交值，原订单不变。
     * @param high 累计成交值高 64 位，-1 表示未提供，不能小于 -1
     * @param low 累计成交值低 64 位，保留原始位模式
     */
    public OrderRuntime withExecutionValue(long high, long low) {
        // 按当前业务值复制的新可变订单，只在副本上设置累计成交值。
        OrderRuntime copy = mutableCopy();
        copy.executionValue(high, low);
        return copy;
    }

    /**
     * 按新的累计成交量、剩余量、状态和版本创建后状态，沿用原费用、累计成交值和提交元数据。
     * @param executed 更新后的累计已成交数量步数
     * @param remaining 更新后的剩余数量步数，两者精确和必须等于原始数量
     * @param nextStatus 本次推进到的订单状态
     * @param nextRevision 本次推进到的正数订单版本
     */
    public OrderRuntime withExecution(long executed, long remaining, CoreOrderStatus nextStatus, long nextRevision) {
        return new OrderRuntime(orderId, productLine, userId, symbolId, instrument, side, priceTicks,
                matchingPriceTicks, quantitySteps, executed, remaining, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm,
                takerFeeRatePpm, cumulativeFeeUnits, executedValueHigh, executedValueLow, createdAtEpochMillis, updatedAtEpochMillis,
                clusterPosition, nextStatus, nextRevision);
    }

    /**
     * 创建包含新增费用的成交后状态，不更新提交时间和位置。
     * @param executed 更新后的累计已成交数量步数
     * @param remaining 更新后的剩余数量步数，两者精确和必须等于原始数量
     * @param feeUnits 本次新增加的手续费整数金额，负数可表示返佣
     * @param nextStatus 本次推进到的订单状态
     * @param nextRevision 本次推进到的正数订单版本
     */
    public OrderRuntime withFill(long executed, long remaining, long feeUnits,
                                 CoreOrderStatus nextStatus, long nextRevision) {
        return withFill(executed, remaining, feeUnits, nextStatus, nextRevision, -1, -1);
    }

    /**
     * 一次创建成交、费用及可选提交元数据的后状态；负提交时间沿用原时间位置，初次有效提交可补创建时间。
     * @param executed 更新后的累计已成交数量步数
     * @param remaining 更新后的剩余数量步数，两者精确和必须等于原始数量
     * @param feeUnits 本次新增加的手续费整数金额，负数可表示返佣
     * @param nextStatus 本次推进到的订单状态
     * @param nextRevision 本次推进到的正数订单版本
     * @param commitTimestamp 本次集群提交时间；不更新元数据的调用使用 -1
     * @param commitPosition 本次集群日志位置；不更新元数据的调用使用 -1
     */
    OrderRuntime withFill(long executed, long remaining, long feeUnits,
                          CoreOrderStatus nextStatus, long nextRevision,
                          long commitTimestamp, long commitPosition) {
        return new OrderRuntime(orderId, productLine, userId, symbolId, instrument, side,
                priceTicks, matchingPriceTicks, quantitySteps, executed, remaining, reduceOnly,
                marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId, commandId,
                makerFeeRatePpm, takerFeeRatePpm, Math.addExact(cumulativeFeeUnits, feeUnits), executedValueHigh, executedValueLow,
                createdAtEpochMillis == 0 && commitTimestamp >= 0 ? commitTimestamp : createdAtEpochMillis,
                commitTimestamp < 0 ? updatedAtEpochMillis : commitTimestamp,
                commitTimestamp < 0 ? clusterPosition : commitPosition, nextStatus, nextRevision);
    }

    /**
     * 保留数量和费用，创建仅状态与版本变化的订单后状态。
     * @param nextStatus 本次推进到的订单状态
     * @param nextRevision 本次推进到的正数订单版本
     */
    public OrderRuntime withStatus(CoreOrderStatus nextStatus, long nextRevision) {
        return withExecution(executedQuantitySteps, remainingQuantitySteps, nextStatus, nextRevision);
    }

    /**
     * 保留数量和费用，在同一新值中更新状态版本及提交时间位置。
     * @param nextStatus 本次推进到的订单状态
     * @param nextRevision 本次推进到的正数订单版本
     * @param commitTimestamp 本次集群提交时间；不更新元数据的调用使用 -1
     * @param commitPosition 本次集群日志位置；不更新元数据的调用使用 -1
     */
    public OrderRuntime withStatus(CoreOrderStatus nextStatus, long nextRevision,
                            long commitTimestamp, long commitPosition) {
        return withFill(executedQuantitySteps, remainingQuantitySteps, 0, nextStatus, nextRevision,
                commitTimestamp, commitPosition);
    }

    /**
     * 创建提交时间位置已更新的后状态，原创建时间非零时保持，费用和累计成交值原样保留。
     * @param timestamp 当前提交或创建订单采用的集群业务毫秒时间
     * @param position 当前命令的集群日志位置
     */
    public OrderRuntime withCommitMetadata(long timestamp, long position) {
        return new OrderRuntime(orderId, productLine, userId, symbolId, instrument, side,
                priceTicks, matchingPriceTicks, quantitySteps, executedQuantitySteps,
                remainingQuantitySteps, reduceOnly, marginMode, positionSide, orderType, timeInForce,
                postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                cumulativeFeeUnits, executedValueHigh, executedValueLow, createdAtEpochMillis == 0 ? timestamp : createdAtEpochMillis,
                timestamp, position, status, revision);
    }

    /**
     * 比较全部订单业务字段和规范币对对象身份，不把池归属、可变标记、派生缓存或常驻冻结槽作为业务差异。
     * @param other 需要与当前订单比较的任意对象
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OrderRuntime /* 类型匹配成功的待比较订单，只比较业务字段。 */ value)) return false;
        return orderId == value.orderId && userId == value.userId && symbolId == value.symbolId
                && instrument == value.instrument && priceTicks == value.priceTicks
                && matchingPriceTicks == value.matchingPriceTicks && quantitySteps == value.quantitySteps
                && executedQuantitySteps == value.executedQuantitySteps
                && remainingQuantitySteps == value.remainingQuantitySteps && reduceOnly == value.reduceOnly
                && postOnly == value.postOnly && makerFeeRatePpm == value.makerFeeRatePpm
                && takerFeeRatePpm == value.takerFeeRatePpm && cumulativeFeeUnits == value.cumulativeFeeUnits
                && executedValueHigh == value.executedValueHigh && executedValueLow == value.executedValueLow
                && createdAtEpochMillis == value.createdAtEpochMillis && updatedAtEpochMillis == value.updatedAtEpochMillis
                && clusterPosition == value.clusterPosition && revision == value.revision
                && productLine == value.productLine && side == value.side && marginMode == value.marginMode
                && positionSide == value.positionSide && orderType == value.orderType && timeInForce == value.timeInForce
                && status == value.status && Objects.equals(clientOrderId, value.clientOrderId)
                && Objects.equals(commandId, value.commandId);
    }

    /**
     * 按与 equals 一致的业务字段和币对对象身份生成哈希值。
     */
    @Override
    public int hashCode() {
        return Objects.hash(orderId, productLine, userId, symbolId, System.identityHashCode(instrument), side, priceTicks,
                matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps,
                reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId,
                commandId, makerFeeRatePpm, takerFeeRatePpm, cumulativeFeeUnits, executedValueHigh, executedValueLow, createdAtEpochMillis,
                updatedAtEpochMillis, clusterPosition, status, revision);
    }

    /**
     * 输出订单身份、产品、币对、价格、数量和状态版本，供诊断和测试断言使用。
     */
    @Override
    public String toString() {
        return "OrderRuntime[orderId=" + orderId + ", productLine=" + productLine + ", userId=" + userId
                + ", symbolId=" + symbolId + ", instrument=" + instrument + ", side=" + side
                + ", priceTicks=" + priceTicks + ", matchingPriceTicks=" + matchingPriceTicks
                + ", quantitySteps=" + quantitySteps + ", executedQuantitySteps=" + executedQuantitySteps
                + ", remainingQuantitySteps=" + remainingQuantitySteps + ", status=" + status
                + ", revision=" + revision + "]";
    }
}
