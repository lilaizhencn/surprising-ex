package com.surprising.aeron.service.export;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreTradeFeeObservation;
import com.surprising.aeron.protocol.RealtimeFrame;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.protocol.export.CommittedBalanceFlow;
import com.surprising.aeron.protocol.export.CommittedCommandResult;
import com.surprising.aeron.protocol.export.CommittedDeliveryPage;
import com.surprising.aeron.protocol.export.CommittedFundingPage;
import com.surprising.aeron.protocol.export.CommittedTradingActivity;
import com.surprising.aeron.protocol.export.LiquidationChange;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.funding.FundingSettlementService;
import com.surprising.aeron.service.query.CommittedTradingActivityCapture;
import com.surprising.product.api.ProductLine;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/**
 * Owner 线程侧轻量投影采集器：在命令提交点收集投影增量并非阻塞投递给 {@link CoreProjectionExporter}。
 * 仅在 Leader 且导出启用时工作，绝不阻塞 Owner 或 Cluster 线程。
 */
@Slf4j
public final class CoreProjectionTap {
    private record BalanceTarget(long userId, String asset, TransferFundsCommand transfer) {}
    private record BeforeDeliveryBalance(long userId, String asset, long totalUnits) {}

    private final CoreProjectionExporter exporter;
    private final long activityIntervalNanos;
    private final ArrayList<LiquidationChange> liquidationChanges = new ArrayList<>();
    private FundingSettlementService.FundingResult fundingResult;
    private BalanceTarget pendingBalanceTarget;
    private BigInteger beforeBalance;
    private SettleInstrumentCommand pendingDeliveryCommand;
    private List<BeforeDeliveryBalance> pendingDeliveryBalances = List.of();
    private long nextActivityNanos;

    public CoreProjectionTap(CoreProjectionExporter exporter, long activityIntervalMillis) {
        this.exporter = exporter;
        this.activityIntervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(250L, activityIntervalMillis));
    }

    public boolean enabled() {
        return exporter != null && exporter.enabled();
    }

    public void attach(TradingCore state) {
        if (!enabled() || state == null) return;
        state.observeReplayFundingPayments(result -> fundingResult = result);
    }

    public void onLiquidation(LiquidationChange change) {
        if (change != null) liquidationChanges.add(change);
    }

    public void beginControlCommand(TradingCore state, CoreMessage command) {
        if (!enabled() || !exporter.isLeader() || command == null) return;
        liquidationChanges.clear();
        fundingResult = null;
        pendingBalanceTarget = balanceTarget(command);
        beforeBalance = pendingBalanceTarget == null ? null
                : balanceUnits(state, pendingBalanceTarget.userId(), pendingBalanceTarget.asset());
        pendingDeliveryCommand = deliveryCommand(state, command);
        pendingDeliveryBalances = pendingDeliveryCommand == null ? List.of()
                : deliveryBalances(state, pendingDeliveryCommand);
    }

    public void beginPipelinedCommit() {
        if (!enabled() || !exporter.isLeader()) return;
        liquidationChanges.clear();
        fundingResult = null;
        pendingBalanceTarget = null;
        beforeBalance = null;
        pendingDeliveryCommand = null;
        pendingDeliveryBalances = List.of();
    }

    public void commitCommand(
            TradingCore state,
            CoreMessage command,
            CoreResponse response,
            long position,
            long timestamp,
            List<RealtimeFrame> orderChanges) {
        if (!enabled() || !exporter.isLeader() || state == null || command == null) {
            liquidationChanges.clear();
            fundingResult = null;
            return;
        }
        try {
            if (command.header().kind() == WireMessageKind.QUERY) return;

            CommittedCommandResult commandResult = buildCommandResult(state, command, response, position, timestamp);
            CommittedBalanceFlow balanceFlow = buildBalanceFlow(state, command, response, commandResult, position, timestamp);
            CommittedFundingPage fundingPage = buildFundingPage(state, command, response, position, timestamp);
            CommittedDeliveryPage deliveryPage = buildDeliveryPage(state, command, response, timestamp);
            List<CoreTradeFeeObservation> feeObservations = buildFeeObservations(state, orderChanges);
            List<LiquidationChange> liquidations = liquidationChanges.isEmpty()
                    ? List.of() : List.copyOf(liquidationChanges);

            CommittedTradingActivity activity = null;
            long nowNanos = System.nanoTime();
            if (nowNanos >= nextActivityNanos) {
                nextActivityNanos = nowNanos + activityIntervalNanos;
                activity = CommittedTradingActivityCapture.capture(state.accountBook(), state.identities(), timestamp);
            }

            if ((orderChanges != null && !orderChanges.isEmpty())
                    || commandResult != null
                    || balanceFlow != null
                    || fundingPage != null
                    || deliveryPage != null
                    || !feeObservations.isEmpty()
                    || !liquidations.isEmpty()
                    || activity != null) {
                exporter.offer(new CoreProjectionExporter.ExportItem(
                        state.realtimeExportSequence(),
                        position,
                        timestamp,
                        orderChanges == null ? List.of() : orderChanges,
                        commandResult,
                        List.of(),
                        balanceFlow,
                        liquidations,
                        feeObservations,
                        fundingPage,
                        deliveryPage,
                        activity));
            }
        } catch (RuntimeException ex) {
            log.warn("CoreProjectionTap skipped command export pos={} type={}",
                    position, command.header().messageType(), ex);
        } finally {
            liquidationChanges.clear();
            fundingResult = null;
            pendingBalanceTarget = null;
            beforeBalance = null;
            pendingDeliveryCommand = null;
            pendingDeliveryBalances = List.of();
        }
    }

    private CommittedCommandResult buildCommandResult(
            TradingCore state, CoreMessage command, CoreResponse response, long position, long timestamp) {
        if (command.header().userId() <= 0 || isRepeatOfFinishedCommand(response)) return null;
        var retained = response != null && response.resultCode() == CoreResultCode.IDEMPOTENCY_CONFLICT
                ? null : state.commandResultLog().get(command.header().commandId());
        if (retained == null && response == null) return null;
        var status = retained == null ? response.commandStatus() : retained.status();
        var code = retained == null ? response.resultCode() : retained.resultCode();
        long applied = retained == null ? response.appliedCommandCount() : retained.appliedCommandCount();
        if (code == CoreResultCode.MATCHING_PENDING) return null;
        if (status != ResponseStatus.APPLIED && status != ResponseStatus.REJECTED) return null;

        String clientOrderId = null;
        String instrumentId = null;
        if (command.header().messageType() == CoreMessageType.PLACE_ORDER && code != CoreResultCode.IDEMPOTENCY_CONFLICT) {
            try {
                var order = TradingCommandCodec.decodePlaceOrder(command.payload());
                clientOrderId = order.clientOrderId();
                instrumentId = order.instrumentId();
            } catch (IllegalArgumentException ignored) {
                if (code != CoreResultCode.INVALID_COMMAND) throw ignored;
            }
        }
        List<CommittedCommandResult.CancelBatchItem> cancelResults = List.of();
        if (command.header().messageType() == CoreMessageType.CANCEL_ORDER_BATCH
                && code != CoreResultCode.IDEMPOTENCY_CONFLICT) {
            var requested = List.<com.surprising.aeron.protocol.CancelOrderCommand>of();
            try {
                requested = TradingOrderBatchCodec.decodeCancelOrderBatch(command.payload()).orders();
            } catch (IllegalArgumentException ignored) {
                if (code != CoreResultCode.INVALID_COMMAND) throw ignored;
            }
            var items = new ArrayList<CommittedCommandResult.CancelBatchItem>(requested.size());
            if (status == ResponseStatus.APPLIED && !requested.isEmpty()) {
                var result = TradingOrderBatchCodec.decodeResult(
                        retained == null ? response.data() : retained.responseData());
                for (var item : result.items()) {
                    long orderId = requested.get(item.index()).orderId();
                    items.add(new CommittedCommandResult.CancelBatchItem(
                            item.index(), orderId, item.status() == ResponseStatus.APPLIED, item.resultCode().name()));
                }
            } else {
                for (int i = 0; i < requested.size(); i++) {
                    items.add(new CommittedCommandResult.CancelBatchItem(
                            i, requested.get(i).orderId(), false, code.name()));
                }
            }
            cancelResults = List.copyOf(items);
        }
        return new CommittedCommandResult(
                state.productLine(), command.header().userId(), command.header().commandId(),
                command.header().messageType(), clientOrderId, instrumentId,
                status, code, applied, position, timestamp, cancelResults);
    }

    private CommittedBalanceFlow buildBalanceFlow(
            TradingCore state, CoreMessage command, CoreResponse response,
            CommittedCommandResult commandResult, long position, long timestamp) {
        if (pendingBalanceTarget == null || beforeBalance == null || commandResult == null
                || commandResult.status() != ResponseStatus.APPLIED) return null;
        var afterBalance = balanceUnits(state, pendingBalanceTarget.userId(), pendingBalanceTarget.asset());
        var delta = afterBalance.subtract(beforeBalance);
        if (delta.signum() != 0 || (command.header().messageType() == CoreMessageType.ADJUST_BALANCE
                && response != null && response.status() == ResponseStatus.APPLIED)) {
            return new CommittedBalanceFlow(
                    state.productLine(), pendingBalanceTarget.userId(), command.header().commandId(),
                    pendingBalanceTarget.asset(), delta.toString(), afterBalance.toString(),
                    position, timestamp, pendingBalanceTarget.transfer());
        }
        return null;
    }

    private CommittedFundingPage buildFundingPage(
            TradingCore state, CoreMessage command, CoreResponse response, long position, long timestamp) {
        if (fundingResult == null || response == null || response.commandStatus() != ResponseStatus.APPLIED) {
            return null;
        }
        return new CommittedFundingPage(
                state.productLine(), position, timestamp,
                TradingCommandCodec.decodeApplyFunding(command.payload()),
                fundingResult.progress(), response.resultCode().name(), fundingResult.payments());
    }

    private CommittedDeliveryPage buildDeliveryPage(
            TradingCore state, CoreMessage command, CoreResponse response, long timestamp) {
        if (pendingDeliveryCommand == null || pendingDeliveryBalances.isEmpty()) return null;
        var retained = state.commandResultLog().get(command.header().commandId());
        var status = retained == null ? (response == null ? null : response.commandStatus()) : retained.status();
        if (status != ResponseStatus.APPLIED) return null;
        var flows = new ArrayList<CommittedDeliveryPage.DeliveryCashFlow>();
        for (var before : pendingDeliveryBalances) {
            long total = balanceUnits(state, before.userId(), before.asset()).longValueExact();
            long amount = Math.subtractExact(total, before.totalUnits());
            if (amount != 0) {
                flows.add(new CommittedDeliveryPage.DeliveryCashFlow(
                        before.userId(), before.asset(), amount, total));
            }
        }
        return flows.isEmpty() ? null : new CommittedDeliveryPage(
                state.productLine(), timestamp, pendingDeliveryCommand, flows);
    }

    private static List<CoreTradeFeeObservation> buildFeeObservations(
            TradingCore state, List<RealtimeFrame> changes) {
        if (changes == null || changes.isEmpty()) return List.of();
        var latest = new LinkedHashMap<Long, CoreOrderStateView>();
        for (var frame : changes) {
            if (frame.kind() != RealtimeFrame.Kind.ORDER) continue;
            var order = CoreStateQueryCodec.decodeOrderState(frame.payload());
            if (order.executedQuantitySteps() == 0) continue;
            var prior = latest.get(order.orderId());
            if (prior == null || prior.revision() < order.revision()) {
                latest.put(order.orderId(), order);
            }
        }
        if (latest.isEmpty()) return List.of();
        var result = new ArrayList<CoreTradeFeeObservation>(latest.size());
        for (var order : latest.values()) {
            var instrument = state.accountBook().instrument(order.instrumentId());
            if (instrument == null) continue;
            String feeAsset = state.productLine() == ProductLine.SPOT
                    ? instrument.quoteAsset() : instrument.settleAsset();
            result.add(new CoreTradeFeeObservation(
                    order.orderId(), order.userId(), order.instrumentId(),
                    feeAsset, order.cumulativeFeeUnits(),
                    order.clusterPosition(), order.updatedAtEpochMillis()));
        }
        return List.copyOf(result);
    }

    private static boolean isRepeatOfFinishedCommand(CoreResponse response) {
        return response != null && response.status() == ResponseStatus.DUPLICATE
                && response.resultCode() != CoreResultCode.STALE_SOURCE_SEQUENCE
                && response.resultCode() != CoreResultCode.MATCHING_PENDING;
    }

    private static BalanceTarget balanceTarget(CoreMessage command) {
        long user = command.header().userId();
        if (user <= 0) return null;
        try {
            return switch (command.header().messageType()) {
                case ADJUST_BALANCE -> new BalanceTarget(user,
                        com.surprising.aeron.model.AssetBalance.normalizeAsset(
                                TradingCommandCodec.decodeBalanceAdjustment(command.payload()).asset()), null);
                case TRANSFER_OUT, TRANSFER_IN -> {
                    var transfer = TradingCommandCodec.decodeTransferFunds(command.payload());
                    long owner = command.header().messageType() == CoreMessageType.TRANSFER_OUT
                            ? transfer.sourceUserId() : transfer.targetUserId();
                    yield owner == user ? new BalanceTarget(user, transfer.asset(), transfer) : null;
                }
                default -> null;
            };
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static SettleInstrumentCommand deliveryCommand(TradingCore state, CoreMessage command) {
        if (!state.productLine().isDeliveryProduct()
                || command.header().messageType() != CoreMessageType.SETTLE_INSTRUMENT) return null;
        try {
            return TradingCommandCodec.decodeSettleInstrument(command.payload());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static BigInteger balanceUnits(TradingCore state, long user, String asset) {
        Integer id = state.identities().findAssetId(asset);
        if (id == null) return BigInteger.ZERO;
        var accountBook = state.accountBook();
        boolean needsAccess = accountBook.accountLanesStarted() && !accountBook.ownerLaneAccess();
        boolean acquired = false;
        try {
            if (needsAccess) {
                accountBook.acquireOwnerLaneAccess();
                acquired = true;
            }
            var balance = accountBook.balance(user, id);
            return balance == null ? BigInteger.ZERO
                    : BigInteger.valueOf(balance.availableUnits()).add(BigInteger.valueOf(balance.lockedUnits()));
        } finally {
            if (acquired) accountBook.releaseOwnerLaneAccess();
        }
    }

    private static List<BeforeDeliveryBalance> deliveryBalances(TradingCore state, SettleInstrumentCommand delivery) {
        var instrument = state.accountBook().instrument(delivery.instrumentId());
        if (instrument == null) return List.of();
        String asset = instrument.settleAsset();
        Integer assetId = state.identities().findAssetId(asset);
        var accountBook = state.accountBook();
        boolean needsAccess = accountBook.accountLanesStarted() && !accountBook.ownerLaneAccess();
        boolean acquired = false;
        try {
            if (needsAccess) {
                accountBook.acquireOwnerLaneAccess();
                acquired = true;
            }
            var users = new ArrayList<BeforeDeliveryBalance>();
            int symbolId = instrument.instrumentIdValue();
            for (int laneId = 0; laneId < accountBook.accountLaneCount(); laneId++) {
                accountBook.onLaneReadOnly(laneId, lane -> {
                    lane.forEachPositionValue(position -> {
                        if (position.symbolId() != symbolId || position.signedQuantitySteps() == 0) return;
                        var b = assetId == null ? null : accountBook.balance(position.userId(), assetId);
                        long total = b == null ? 0L : Math.addExact(b.availableUnits(), b.lockedUnits());
                        users.add(new BeforeDeliveryBalance(position.userId(), asset, total));
                    });
                    return null;
                });
            }
            return List.copyOf(users);
        } finally {
            if (acquired) accountBook.releaseOwnerLaneAccess();
        }
    }
}
