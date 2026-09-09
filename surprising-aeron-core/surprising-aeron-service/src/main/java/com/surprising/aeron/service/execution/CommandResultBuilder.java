package com.surprising.aeron.service.execution;

import static com.surprising.aeron.service.execution.TradingCoreRuntime.*;

import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreFundingProgressCodec;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreLiquidationProgressCodec;
import com.surprising.aeron.protocol.CoreLiquidationProgressView;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultCodec;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultView;
import com.surprising.aeron.protocol.CoreSettlementProgressCodec;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.CoreRiskScanControlCodec;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.service.state.OrderRuntime;
import java.util.ArrayList;
import java.util.List;

/** 当前命令的结果与变更 ID；owner 串行复用，在命令完成边界编码。 */
final class CommandResultBuilder {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingCoreRuntime owner;

    CommandResultBuilder(TradingCoreRuntime owner) { this.owner = owner; }

    /** 当前命令需要返回的订单视图，仅响应边界物化。 */
    List<CoreOrderStateView> commandOrderViews = List.of();

    /** 当前命令返回的用户 ID 集合；完成边界生成。 */
    List<Long> commandChangedUserIds;

    /** 当前命令返回的订单 ID 集合；完成边界生成。 */
    List<Long> commandChangedOrderIds;

    /** 当前命令变化的用户 ID，保持 primitive 收集直到返回边界。 */
    final PrimitiveLongChangeSet changedUserIds = new PrimitiveLongChangeSet();

    /** 当前命令变化的订单 ID，保持 primitive 收集直到返回边界。 */
    final PrimitiveLongChangeSet changedOrderIds = new PrimitiveLongChangeSet();

    /** 当前命令产生的成交数。 */
    long commandTradeCount;

    /** 当前资金费命令的续作进度。 */
    CoreFundingProgressView commandFundingProgress;

    /** 当前清算命令的续作进度。 */
    CoreLiquidationProgressView commandLiquidationProgress;

    /** 当前批量清算的逐项结果。 */
    CoreLiquidationBatchResultView commandLiquidationBatchResult;

    /** 当前到期结算命令的续作进度。 */
    CoreSettlementProgressView commandSettlementProgress;

    /** 当前风险扫描控制命令的返回状态。 */
    CoreRiskScanControlView commandRiskScanControl;

    /** 当前触发单命令返回的终态视图。 */
    com.surprising.aeron.protocol.CoreTriggerOrderStateView commandTriggerOrderView;

    void markUserChanged(long userId) {
        owner.seedChangeAccumulators();
        changedUserIds.add(userId);
    }

    void markOrderChanged(long orderId) {
        owner.seedChangeAccumulators();
        changedOrderIds.add(orderId);
    }

    void resetChangeAccumulators() {
        owner.commandFundsAccumulator.clear();
        owner.commandExternalAdjustment = false;
        changedUserIds.clear();
        changedOrderIds.clear();
    }

    void materializeChangeAccumulators() {
        owner.seedChangeAccumulators();
        owner.runtimeState.acceptChangedUserIds(changedUserIds::add);
        commandChangedUserIds = changedUserIds.toImmutableList();
        commandChangedOrderIds = changedOrderIds.toImmutableList();
    }

    void materializeCommandOrderViews(PendingMatching pending) {
        switch (pending.operation()) {
            case PLACE -> {
                materializeResponseOrder(pending.decodedCommand().placeOrder().orderId());
                return;
            }
            case CANCEL -> {
                materializeResponseOrder(pending.decodedCommand().cancelOrder().orderId());
                return;
            }
            case REPLACE, AMEND -> {
                ResolvedMatchingAdmission admission = owner.requireMatchingAdmission(pending);
                materializeResponseOrders(admission.originalOrderId(), admission.command().orderId());
                return;
            }
            case TRIGGER -> {
                materializeResponseOrder(pending.settlementPlan().takerOrderId());
                return;
            }
            default -> { }
        }
        PrimitiveLongChangeSet orderIds = new PrimitiveLongChangeSet();
        java.util.ArrayList<CoreOrderStateView> views = new java.util.ArrayList<>(
                commandOrderViews.size() + commandChangedOrderIds.size());
        for (CoreOrderStateView view : commandOrderViews) {
            if (orderIds.add(view.orderId())) views.add(view);
        }
        for (int orderIndex = 0; orderIndex < commandChangedOrderIds.size(); orderIndex++) {
            long orderId = commandChangedOrderIds instanceof ImmutableLongArrayList primitive
                    ? primitive.valueAt(orderIndex) : commandChangedOrderIds.get(orderIndex);
            var order = owner.responseOrder(orderId);
            if (order == null) continue;
            CoreOrderStateView view = owner.orderView(order);
            if (orderIds.add(orderId)) {
                views.add(view);
                continue;
            }
            for (int index = 0; index < views.size(); index++) {
                if (views.get(index).orderId() == orderId) {
                    views.set(index, view);
                    break;
                }
            }
        }
        commandOrderViews = List.copyOf(views);
    }

    void materializeResponseOrders(long... orderIds) {
        java.util.ArrayList<CoreOrderStateView> views = new java.util.ArrayList<>(orderIds.length);
        for (long orderId : orderIds) {
            OrderRuntime order = owner.responseOrder(orderId);
            if (order != null) views.add(owner.orderView(order));
        }
        commandOrderViews = List.copyOf(views);
    }

    void materializeResponseOrder(long orderId) {
        OrderRuntime order = owner.responseOrder(orderId);
        commandOrderViews = order == null ? List.of() : List.of(owner.orderView(order));
    }

    byte[] commandResultData() {
        return commandResultData(null, null);
    }

    byte[] commandResultData(
            PendingMatching pending,
            com.surprising.aeron.service.matching.CoreMatchingResult matchingResult) {
        if (commandRiskScanControl != null) {
            return CoreRiskScanControlCodec.encodeView(commandRiskScanControl);
        }
        if (commandFundingProgress != null) {
            return CoreFundingProgressCodec.encode(commandFundingProgress);
        }
        if (commandLiquidationProgress != null) {
            return CoreLiquidationProgressCodec.encode(commandLiquidationProgress);
        }
        if (commandLiquidationBatchResult != null) {
            return CoreLiquidationBatchResultCodec.encode(commandLiquidationBatchResult);
        }
        if (commandSettlementProgress != null) {
            return CoreSettlementProgressCodec.encode(commandSettlementProgress);
        }
        if (commandTriggerOrderView != null) {
            return com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(List.of(commandTriggerOrderView));
        }
        if (commandOrderViews.isEmpty()) {
            return new byte[0];
        }
        if (pending == null || matchingResult == null) {
            return new byte[0];
        }
        var nativeCommand = matchingResult.nativeCommand();
        var matcherPrefix = matchingResult.matcherPrefix();
        if (nativeCommand.coreSequence() != pending.sequence()
                || !nativeCommand.matches(pending.command().header().commandId())
                || nativeCommand.orderId() <= 0 || nativeCommand.instrumentChangeId() <= 0
                || nativeCommand.matcherSequence() <= 0 || !matcherPrefix.bound()) {
            return new byte[0];
        }
        try {
            return CoreCommandResultCodec.encode(
                    pending.sequence(), pending.command().header().commandId(),
                    nativeCommand.orderId(), nativeCommand.instrumentChangeId(), nativeCommand.matcherSequence(),
                    matcherPrefix.before(), matcherPrefix.after(), commandOrderViews, List.of());
        } catch (IllegalArgumentException exception) {
            return new byte[0];
        }
    }
}
