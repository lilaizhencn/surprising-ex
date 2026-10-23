package com.surprising.aeron.model;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreTriggerCondition;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.protocol.CoreTriggerPriceSource;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.product.api.ProductLine;

/**
 * 条件单和追踪止损的不可变业务记录，保存触发条件、价格来源、追踪极值及触发后的普通订单结果。扫描、关联撤单和实际下单在业务流程中执行。
 * @param triggerOrderId 条件单的唯一编号，与触发后产生的普通订单编号分开保存
 * @param userId 这份状态所属的用户编号，必须为正数
 * @param clientTriggerOrderId 客户端提供的条件单标识，未提供时保存为空字符串
 * @param ocoGroupId 关联撤销组标识；组内一笔触发后会处理其余关联条件单
 * @param instrument 该条件单绑定的标的，提供固定编号和产品归属
 * @param side 订单的买入或卖出方向
 * @param triggerType 普通止盈止损或追踪止损等条件单类型
 * @param triggerCondition 条件价格要满足的大于等于或小于等于等比较方向
 * @param triggerPriceTicks 普通条件单的触发门槛价格 tick；追踪单还要结合激活价和回调比例
 * @param activationPriceTicks 追踪止损开始跟踪前需要达到的激活价格 tick
 * @param callbackRatePpm 追踪止损从价格极值回撤的触发比例，单位为百万分之一
 * @param highestPriceTicks 追踪过程中记录的最高参考价格 tick，卖出追踪止损使用
 * @param lowestPriceTicks 追踪过程中记录的最低参考价格 tick，买入追踪止损使用
 * @param activatedAtEpochMillis 追踪单首次激活时的 Unix 毫秒时间戳，0 表示尚未记录激活
 * @param orderType 限价、市价等执行订单类型
 * @param timeInForce 订单的有效方式，例如持续挂单、立即成交或撤销、只做挂单方
 * @param priceTicks 委托价格，单位为最小价格 tick，具体可否为零由订单类型决定
 * @param quantitySteps 委托总数量，以币对配置的最小数量步数表示
 * @param marginMode 使用全仓还是逐仓保证金；两种模式的可用权益不能混算
 * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
 * @param status 条件单当前等待、触发、取消或拒绝等协议状态
 * @param placedOrderId 条件满足后实际产生的普通订单编号
 * @param triggerSequence 本次触发采用的行情或触发处理序号，用来关联触发结果
 * @param triggeredPriceTicks 实际满足触发条件时的参考价格 tick
 * @param rejectReason 被拒绝时记录的原因；没有原因时保存为空字符串
 * @param expiresAtEpochMillis 条件单到期的 Unix 毫秒时间戳，0 表示不设到期时间
 * @param triggeredAtEpochMillis 实际触发条件单的 Unix 毫秒时间戳
 * @param createdAtEpochMillis 这份业务记录的创建时间，使用 Unix 毫秒时间戳
 * @param updatedAtEpochMillis 最后一次更新这份记录的 Unix 毫秒时间戳
 * @param revision 这份状态的修订号，供结果发布和后续更新判断版本
 * @param makerFeeRatePpm 挂单成交时采用的费率，单位为百万分之一，负数表示返佣
 * @param takerFeeRatePpm 吃单成交时采用的费率，单位为百万分之一，负数表示返佣
 * @param priceSource 判断条件时使用标记价、最新成交价还是指数价
 */
public record CoreTriggerOrderState(
        long triggerOrderId, long userId, String clientTriggerOrderId, String ocoGroupId,
        CoreInstrument instrument, CoreOrderSide side, CoreTriggerOrderType triggerType,
        CoreTriggerCondition triggerCondition,
        long triggerPriceTicks, long activationPriceTicks, long callbackRatePpm, long highestPriceTicks,
        long lowestPriceTicks, long activatedAtEpochMillis, CoreOrderType orderType, CoreTimeInForce timeInForce,
        long priceTicks, long quantitySteps, CoreMarginMode marginMode, CorePositionSide positionSide,
        CoreTriggerOrderStatus status, long placedOrderId, long triggerSequence, long triggeredPriceTicks,
        String rejectReason, long expiresAtEpochMillis, long triggeredAtEpochMillis,
        long createdAtEpochMillis, long updatedAtEpochMillis, long revision,
        long makerFeeRatePpm, long takerFeeRatePpm, CoreTriggerPriceSource priceSource) {

    /**
     * 创建尚未提供执行费率的条件单，挂单与吃单费率从 0 开始，触发价格来源使用标记价。
     * @param triggerOrderId 条件单的唯一编号，与触发后产生的普通订单编号分开保存
     * @param userId 这份状态所属的用户编号，必须为正数
     * @param clientTriggerOrderId 客户端提供的条件单标识，未提供时保存为空字符串
     * @param ocoGroupId 关联撤销组标识；组内一笔触发后会处理其余关联条件单
     * @param instrument 该条件单绑定的标的，提供固定编号和产品归属
     * @param side 订单的买入或卖出方向
     * @param triggerType 普通止盈止损或追踪止损等条件单类型
     * @param triggerCondition 条件价格要满足的大于等于或小于等于等比较方向
     * @param triggerPriceTicks 普通条件单的触发门槛价格 tick；追踪单还要结合激活价和回调比例
     * @param activationPriceTicks 追踪止损开始跟踪前需要达到的激活价格 tick
     * @param callbackRatePpm 追踪止损从价格极值回撤的触发比例，单位为百万分之一
     * @param highestPriceTicks 追踪过程中记录的最高参考价格 tick，卖出追踪止损使用
     * @param lowestPriceTicks 追踪过程中记录的最低参考价格 tick，买入追踪止损使用
     * @param activatedAtEpochMillis 追踪单首次激活时的 Unix 毫秒时间戳，0 表示尚未记录激活
     * @param orderType 限价、市价等执行订单类型
     * @param timeInForce 订单的有效方式，例如持续挂单、立即成交或撤销、只做挂单方
     * @param priceTicks 委托价格，单位为最小价格 tick，具体可否为零由订单类型决定
     * @param quantitySteps 委托总数量，以币对配置的最小数量步数表示
     * @param marginMode 使用全仓还是逐仓保证金；两种模式的可用权益不能混算
     * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
     * @param status 条件单当前等待、触发、取消或拒绝等协议状态
     * @param placedOrderId 条件满足后实际产生的普通订单编号
     * @param triggerSequence 本次触发采用的行情或触发处理序号，用来关联触发结果
     * @param triggeredPriceTicks 实际满足触发条件时的参考价格 tick
     * @param rejectReason 被拒绝时记录的原因；没有原因时保存为空字符串
     * @param expiresAtEpochMillis 条件单到期的 Unix 毫秒时间戳，0 表示不设到期时间
     * @param triggeredAtEpochMillis 实际触发条件单的 Unix 毫秒时间戳
     * @param createdAtEpochMillis 这份业务记录的创建时间，使用 Unix 毫秒时间戳
     * @param updatedAtEpochMillis 最后一次更新这份记录的 Unix 毫秒时间戳
     * @param revision 这份状态的修订号，供结果发布和后续更新判断版本
     */
    public CoreTriggerOrderState(long triggerOrderId, long userId,
                                 String clientTriggerOrderId, String ocoGroupId,
                                 CoreInstrument instrument, CoreOrderSide side,
                                 CoreTriggerOrderType triggerType, CoreTriggerCondition triggerCondition,
                                 long triggerPriceTicks, long activationPriceTicks, long callbackRatePpm,
                                 long highestPriceTicks, long lowestPriceTicks, long activatedAtEpochMillis,
                                 CoreOrderType orderType, CoreTimeInForce timeInForce, long priceTicks,
                                 long quantitySteps, CoreMarginMode marginMode, CorePositionSide positionSide,
                                 CoreTriggerOrderStatus status, long placedOrderId, long triggerSequence,
                                 long triggeredPriceTicks, String rejectReason,
                                 long expiresAtEpochMillis, long triggeredAtEpochMillis, long createdAtEpochMillis,
                                 long updatedAtEpochMillis, long revision) {
        this(triggerOrderId, userId, clientTriggerOrderId, ocoGroupId, instrument, side, triggerType,
                triggerCondition, triggerPriceTicks, activationPriceTicks, callbackRatePpm, highestPriceTicks,
                lowestPriceTicks, activatedAtEpochMillis, orderType, timeInForce, priceTicks, quantitySteps,
                marginMode, positionSide, status, placedOrderId, triggerSequence, triggeredPriceTicks,
                rejectReason, expiresAtEpochMillis, triggeredAtEpochMillis, createdAtEpochMillis,
                updatedAtEpochMillis, revision, 0, 0, CoreTriggerPriceSource.MARK);
    }

    /**
     * 将可空标识和拒绝原因统一为空字符串，校验用户、绑定标的、数量、状态、价格来源和费率范围。
     */
    public CoreTriggerOrderState {
        clientTriggerOrderId = clientTriggerOrderId == null ? "" : clientTriggerOrderId;
        ocoGroupId = ocoGroupId == null ? "" : ocoGroupId;
        rejectReason = rejectReason == null ? "" : rejectReason;
        if (triggerOrderId <= 0 || userId <= 0 || instrument == null
                || side == null || triggerType == null || triggerCondition == null || triggerPriceTicks < 0
                || callbackRatePpm < 0 || orderType == null || timeInForce == null || quantitySteps <= 0
                || marginMode == null || positionSide == null || status == null || priceSource == null || revision < 0 || makerFeeRatePpm < -1_000_000L || makerFeeRatePpm > 1_000_000L
                || takerFeeRatePpm < -1_000_000L || takerFeeRatePpm > 1_000_000L) {
            throw new IllegalArgumentException("invalid trigger order state");
        }
    }

    /** 标的固定编号由绑定的权威标的提供。 */
    public String instrumentId() {
        return instrument.instrumentId();
    }

    /** 标的的产品归属固定，配置更新不改变合约类型。 */
    public ProductLine productLine() {
        return instrument.contractType().productLine();
    }

    /**
     * 生成协议层的条件单只读视图，保留触发结果和费用快照，不把内部币对配置对象放进视图。
     */
    public CoreTriggerOrderStateView view() {
        return new CoreTriggerOrderStateView(triggerOrderId, productLine(), userId, clientTriggerOrderId, ocoGroupId,
                instrumentId(), side, triggerType, triggerCondition, triggerPriceTicks, activationPriceTicks, callbackRatePpm,
                highestPriceTicks, lowestPriceTicks, activatedAtEpochMillis, orderType, timeInForce, priceTicks,
                quantitySteps, marginMode, positionSide, status, placedOrderId, triggerSequence, triggeredPriceTicks,
                rejectReason, expiresAtEpochMillis, triggeredAtEpochMillis, createdAtEpochMillis,
                updatedAtEpochMillis, revision, makerFeeRatePpm, takerFeeRatePpm, priceSource);
    }

    /**
     * 从协议视图恢复条件单，并补入与编号一致的币对配置，重新经过构造器校验。
     * @param view 包含条件单业务字段的协议视图，还原时另补币对配置
     * @param instrument 该条件单绑定的标的，提供固定编号和产品归属
     */
    public static CoreTriggerOrderState from(CoreTriggerOrderStateView view, CoreInstrument instrument) {
        if (view == null || instrument == null || !instrument.instrumentId().equals(view.instrumentId())
                || instrument.contractType().productLine() != view.productLine()) {
            throw new IllegalArgumentException("trigger order instrument mismatch");
        }
        return new CoreTriggerOrderState(view.triggerOrderId(), view.userId(),
                view.clientTriggerOrderId(), view.ocoGroupId(), instrument, view.side(), view.triggerType(),
                view.triggerCondition(), view.triggerPriceTicks(), view.activationPriceTicks(), view.callbackRatePpm(),
                view.highestPriceTicks(), view.lowestPriceTicks(), view.activatedAtEpochMillis(), view.orderType(),
                view.timeInForce(), view.priceTicks(), view.quantitySteps(), view.marginMode(), view.positionSide(),
                view.status(), view.placedOrderId(), view.triggerSequence(), view.triggeredPriceTicks(),
                view.rejectReason(), view.expiresAtEpochMillis(), view.triggeredAtEpochMillis(),
                view.createdAtEpochMillis(), view.updatedAtEpochMillis(), view.revision(),
                view.makerFeeRatePpm(), view.takerFeeRatePpm(), view.priceSource());
    }

    /**
     * 返回更新了挂单、吃单费率快照的条件单，保留原触发进度、时间和修订号。
     * @param makerFeeRatePpm 挂单成交时采用的费率，单位为百万分之一，负数表示返佣
     * @param takerFeeRatePpm 吃单成交时采用的费率，单位为百万分之一，负数表示返佣
     */
    public CoreTriggerOrderState withExecutionSnapshot(long makerFeeRatePpm,
                                                       long takerFeeRatePpm) {
        return new CoreTriggerOrderState(triggerOrderId, userId, clientTriggerOrderId, ocoGroupId,
                instrument, side, triggerType, triggerCondition, triggerPriceTicks, activationPriceTicks,
                callbackRatePpm, highestPriceTicks, lowestPriceTicks, activatedAtEpochMillis, orderType,
                timeInForce, priceTicks, quantitySteps, marginMode, positionSide, status, placedOrderId,
                triggerSequence, triggeredPriceTicks, rejectReason, expiresAtEpochMillis,
                triggeredAtEpochMillis, createdAtEpochMillis, updatedAtEpochMillis, revision,
                makerFeeRatePpm, takerFeeRatePpm, priceSource);
    }
}
