package com.surprising.aeron.service.state.model;

import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.service.state.order.OrderReservation;
import java.util.List;

/**
 * 算法母单的不可变状态，保存总目标、切片计划、子单关系和已经退役子单的成交进度；它只记录事实，定时拆单由算法订单流程负责。
 * @param algoOrderId 算法母单的唯一编号
 * @param userId 这份状态所属的用户编号，必须为正数
 * @param clientAlgoOrderId 客户端为算法母单提供的标识，未提供时保存为空字符串
 * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
 * @param algoTypeCode 协议约定的算法类型编号，用来选择母单的拆单方式
 * @param side 订单的买入或卖出方向
 * @param priceTicks 委托价格，单位为最小价格 tick，具体可否为零由订单类型决定
 * @param quantitySteps 委托总数量，以币对配置的最小数量步数表示
 * @param childQuantitySteps 每个切片子单计划使用的数量步数
 * @param intervalSeconds 两次切片调度之间的间隔秒数
 * @param durationSeconds 母单计划执行的总时长，单位为秒
 * @param marginMode 使用全仓还是逐仓保证金；两种模式的可用权益不能混算
 * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
 * @param reduceOnly 是否只允许减少已有持仓，不能借这笔订单增加反向或同向风险
 * @param postOnly 是否要求只做挂单方，不能立即吃掉对手盘
 * @param timeInForce 订单的有效方式，例如持续挂单、立即成交或撤销、只做挂单方
 * @param statusCode 协议约定的母单状态编号，3、4、5 视为终态
 * @param currentOrderId 母单当前关联的执行子单编号
 * @param rejectReason 被拒绝时记录的原因；没有原因时保存为空字符串
 * @param startAtEpochMillis 计划开始执行母单的 Unix 毫秒时间戳
 * @param nextSliceAtEpochMillis 下一次计划发出切片子单的 Unix 毫秒时间戳
 * @param completedAtEpochMillis 母单结束时记录的 Unix 毫秒时间戳
 * @param createdAtEpochMillis 这份业务记录的创建时间，使用 Unix 毫秒时间戳
 * @param updatedAtEpochMillis 最后一次更新这份记录的 Unix 毫秒时间戳
 * @param revision 这份状态的修订号，供结果发布和后续更新判断版本
 * @param childOrderIds 母单关联的子单编号列表，构造时复制为不可修改列表
 * @param retiredExecutedQuantitySteps 已退出子单列表的历史子单累计成交步数，避免移除子单后丢失母单进度
 * @param pendingClosedChildId 已经关闭、仍待从母单关联中处理的子单编号，0 表示没有待处理项
 */
public record CoreAlgoOrderState(
        long algoOrderId, long userId, String clientAlgoOrderId, String instrumentId, int algoTypeCode,
        CoreOrderSide side, long priceTicks, long quantitySteps, long childQuantitySteps,
        long intervalSeconds, long durationSeconds, CoreMarginMode marginMode, CorePositionSide positionSide,
        boolean reduceOnly, boolean postOnly, CoreTimeInForce timeInForce, int statusCode,
        long currentOrderId, String rejectReason, long startAtEpochMillis,
        long nextSliceAtEpochMillis, long completedAtEpochMillis, long createdAtEpochMillis,
        long updatedAtEpochMillis, long revision, List<Long> childOrderIds,
        long retiredExecutedQuantitySteps, long pendingClosedChildId) {

    /**
     * 创建初始母单状态，历史退役子单成交量和待处理的已关闭子单编号都设为 0。
     * @param algoOrderId 算法母单的唯一编号
     * @param userId 这份状态所属的用户编号，必须为正数
     * @param clientAlgoOrderId 客户端为算法母单提供的标识，未提供时保存为空字符串
     * @param instrumentId 币对或合约编号，用于关联该产品的配置与状态
     * @param algoTypeCode 协议约定的算法类型编号，用来选择母单的拆单方式
     * @param side 订单的买入或卖出方向
     * @param priceTicks 委托价格，单位为最小价格 tick，具体可否为零由订单类型决定
     * @param quantitySteps 委托总数量，以币对配置的最小数量步数表示
     * @param childQuantitySteps 每个切片子单计划使用的数量步数
     * @param intervalSeconds 两次切片调度之间的间隔秒数
     * @param durationSeconds 母单计划执行的总时长，单位为秒
     * @param marginMode 使用全仓还是逐仓保证金；两种模式的可用权益不能混算
     * @param positionSide 净持仓、多头腿或空头腿；双向持仓时用于区分同一合约的两条腿
     * @param reduceOnly 是否只允许减少已有持仓，不能借这笔订单增加反向或同向风险
     * @param postOnly 是否要求只做挂单方，不能立即吃掉对手盘
     * @param timeInForce 订单的有效方式，例如持续挂单、立即成交或撤销、只做挂单方
     * @param statusCode 协议约定的母单状态编号，3、4、5 视为终态
     * @param currentOrderId 母单当前关联的执行子单编号
     * @param rejectReason 被拒绝时记录的原因；没有原因时保存为空字符串
     * @param startAtEpochMillis 计划开始执行母单的 Unix 毫秒时间戳
     * @param nextSliceAtEpochMillis 下一次计划发出切片子单的 Unix 毫秒时间戳
     * @param completedAtEpochMillis 母单结束时记录的 Unix 毫秒时间戳
     * @param createdAtEpochMillis 这份业务记录的创建时间，使用 Unix 毫秒时间戳
     * @param updatedAtEpochMillis 最后一次更新这份记录的 Unix 毫秒时间戳
     * @param revision 这份状态的修订号，供结果发布和后续更新判断版本
     * @param childOrderIds 母单关联的子单编号列表，构造时复制为不可修改列表
     */
    public CoreAlgoOrderState(
        long algoOrderId, long userId, String clientAlgoOrderId, String instrumentId, int algoTypeCode,
        CoreOrderSide side, long priceTicks, long quantitySteps, long childQuantitySteps,
        long intervalSeconds, long durationSeconds, CoreMarginMode marginMode, CorePositionSide positionSide,
        boolean reduceOnly, boolean postOnly, CoreTimeInForce timeInForce, int statusCode,
        long currentOrderId, String rejectReason, long startAtEpochMillis,
        long nextSliceAtEpochMillis, long completedAtEpochMillis, long createdAtEpochMillis,
        long updatedAtEpochMillis, long revision, List<Long> childOrderIds) {
        this(algoOrderId,userId,clientAlgoOrderId,instrumentId,algoTypeCode,side,priceTicks,quantitySteps,
                childQuantitySteps,intervalSeconds,durationSeconds,marginMode,positionSide,reduceOnly,postOnly,
                timeInForce,statusCode,currentOrderId,rejectReason,startAtEpochMillis,nextSliceAtEpochMillis,
                completedAtEpochMillis,createdAtEpochMillis,updatedAtEpochMillis,revision,childOrderIds,0,0);
    }

    /**
     * 返回更新了退役子单累计成交量和待处理关闭子单编号的新母单，其余执行计划及修订号保持原值。
     * @param executed 已经退役的子单累计成交步数，用于更新母单进度
     * @param pendingChild 待处理的已关闭子单编号，0 表示没有待处理子单
     */
    public CoreAlgoOrderState withRetiredProgress(long executed, long pendingChild) {
        return new CoreAlgoOrderState(algoOrderId,userId,clientAlgoOrderId,instrumentId,algoTypeCode,side,
                priceTicks,quantitySteps,childQuantitySteps,intervalSeconds,durationSeconds,marginMode,positionSide,
                reduceOnly,postOnly,timeInForce,statusCode,currentOrderId,rejectReason,startAtEpochMillis,
                nextSliceAtEpochMillis,completedAtEpochMillis,createdAtEpochMillis,updatedAtEpochMillis,revision,
                childOrderIds,executed,pendingChild);
    }

    /**
     * 补齐可空文本、复制子单列表，并校验母单数量、执行时长、时间、历史进度及每个子单编号。
     */
    public CoreAlgoOrderState {
        clientAlgoOrderId = clientAlgoOrderId == null ? "" : clientAlgoOrderId;
        instrumentId = OrderReservation.requireInstrumentId(instrumentId);
        rejectReason = rejectReason == null ? "" : rejectReason;
        childOrderIds = List.copyOf(childOrderIds);
        if (algoOrderId <= 0 || userId <= 0 || algoTypeCode < 0 || side == null || quantitySteps <= 0
                || childQuantitySteps <= 0 || intervalSeconds <= 0 || durationSeconds <= 0 || marginMode == null
                || positionSide == null || timeInForce == null || statusCode < 0 || startAtEpochMillis <= 0
                || createdAtEpochMillis <= 0 || updatedAtEpochMillis <= 0 || revision < 0
                || retiredExecutedQuantitySteps < 0 || retiredExecutedQuantitySteps > quantitySteps || pendingClosedChildId < 0) {
            throw new IllegalArgumentException("invalid algo order state");
        }
        for (/* 当前检查的子单编号在列表中的下标。 */ int i = 0; i < childOrderIds.size(); i++) {
            // 本轮读取的子单编号，必须存在且为正数。
            Long value = childOrderIds.get(i);
            if (value == null || value <= 0) {
                throw new IllegalArgumentException("invalid algo order state");
            }
        }
    }

    /**
     * 从协议视图还原母单字段；视图不含退役进度，因此这两项按初始值 0 构造。
     * @param value 需要还原成内部母单记录的协议视图
     */
    public static CoreAlgoOrderState from(CoreAlgoOrderView value) {
        return new CoreAlgoOrderState(value.algoOrderId(), value.userId(), value.clientAlgoOrderId(), value.instrumentId(),
                value.algoTypeCode(), value.side(), value.priceTicks(), value.quantitySteps(), value.childQuantitySteps(),
                value.intervalSeconds(), value.durationSeconds(), value.marginMode(), value.positionSide(), value.reduceOnly(),
                value.postOnly(), value.timeInForce(), value.statusCode(), value.currentOrderId(), value.rejectReason(),
                value.startAtEpochMillis(), value.nextSliceAtEpochMillis(), value.completedAtEpochMillis(),
                value.createdAtEpochMillis(), value.updatedAtEpochMillis(), value.revision(), value.childOrderIds());
    }

    /**
     * 按协议状态编号判断母单是否结束，3、4、5 都不再作为运行中母单处理。
     */
    public boolean terminal() {
        return statusCode == 3 || statusCode == 4 || statusCode == 5;
    }
}
