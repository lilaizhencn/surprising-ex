package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.account.BalanceRuntime;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.market.MarkPriceRuntime;

import com.surprising.aeron.service.state.risk.*;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.math.*;

import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreRiskStatus;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;

import java.util.Collection;
import java.util.List;

/** Liquidation account mutations run on their Lane; Owner applies treasury deltas after completion. */
public final class RuntimeDerivativeLiquidationProcessor {

    private RuntimeDerivativeLiquidationProcessor() {
    }



    public static TradingRuntimeState applyExecution(TradingCoreState before,
                                                     ExecuteLiquidationCommand command,
                                                     Collection<CoreOrderState> canceledOrders,
                                                     TradingRuntimeState runtime,
                                                     RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual liquidation apply");
        }
        return applyExecutionRuntime(command, canceledOrders, runtime, identities);
    }

    public static TradingRuntimeState applyExecutionRuntime(ExecuteLiquidationCommand command,
                                                            Collection<CoreOrderState> canceledOrders,
                                                            TradingRuntimeState runtime,
                                                            RuntimeIdentityRegistry identities) {
        execute(null, command, canceledOrders, 0, runtime, identities, false);
        return runtime;
    }

    /** Re-arm a command-slot execution continuation while retaining its validation buffers. */
    public static ExecutionWork beginExecution(ExecutionWork reuse, ExecuteLiquidationCommand command,
            Collection<CoreOrderState> canceledOrders, long nextCursorOrderId,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        return execute(reuse, command, canceledOrders, nextCursorOrderId, runtime, identities, true);
    }

    /** A validated account stage that can be coalesced with other liquidation stages. */
    public record ExecutionRequest(ExecuteLiquidationCommand command,
                                   Collection<CoreOrderState> canceledOrders,
                                   long nextCursorOrderId) {
        public ExecutionRequest {
            if (command == null || nextCursorOrderId < 0) {
                throw new IllegalArgumentException("invalid liquidation batch request");
            }
        }
    }

    /**
     * Dispatch several independent liquidation account stages together. Each Account Lane
     * still owns all of its mutations, while the Owner pays one dispatch/poll cost for the batch.
     */
    public static java.util.function.BooleanSupplier beginExecutionBatch(
            List<ExecutionRequest> requests, TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        if (requests == null || requests.isEmpty() || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid liquidation execution batch");
        }
        runtime.assertOwner();
        BatchExecutionStage[] stages = new BatchExecutionStage[requests.size()];
        long laneMask = 0;
        for (int index = 0; index < requests.size(); index++) {
            stages[index] = BatchExecutionStage.prepare(requests.get(index), runtime, identities);
            laneMask |= 1L << stages[index].laneId;
        }
        RuntimeTreasuryDelta[] results = new RuntimeTreasuryDelta[stages.length];
        runtime.dispatchControlLanes(laneMask, lane -> {
            for (int index = 0; index < stages.length; index++) {
                BatchExecutionStage stage = stages[index];
                if (stage.laneId == lane) results[index] = stage.apply();
            }
            return results;
        });
        return () -> {
            if (!runtime.pollControlLanes()) return false;
            for (int index = 0; index < stages.length; index++) {
                BatchExecutionStage stage = stages[index];
                finishExecution(runtime, stage.userId, stage.assetId, stage.positionKey,
                        results[index], stage.canceledCount);
            }
            return true;
        };
    }

    private static final class BatchExecutionStage {
        private ExecuteLiquidationCommand command;
        private long[] orderIds;
        private long nextCursorOrderId;
        private int canceledCount;
        private boolean advance;
        private boolean obsolete;
        private TradingRuntimeState runtime;
        private LiquidationRuntime liquidation;
        private CoreInstrument instrument;
        private int assetId;
        private long positionKey;
        private int laneId;
        private long userId;

        private BatchExecutionStage(ExecuteLiquidationCommand command, long[] orderIds,
                                    long nextCursorOrderId, boolean advance, boolean obsolete,
                                    TradingRuntimeState runtime, LiquidationRuntime liquidation,
                                    CoreInstrument instrument, int assetId, long positionKey) {
            reset(command, orderIds, nextCursorOrderId, advance, obsolete, runtime, liquidation,
                    instrument, assetId, positionKey);
        }

        private void reset(ExecuteLiquidationCommand command, long[] orderIds,
                           long nextCursorOrderId, boolean advance, boolean obsolete,
                           TradingRuntimeState runtime, LiquidationRuntime liquidation,
                           CoreInstrument instrument, int assetId, long positionKey) {
            this.command = command; this.orderIds = orderIds; this.nextCursorOrderId = nextCursorOrderId;
            this.canceledCount = orderIds == null ? 0 : orderIds.length;
            this.advance = advance; this.obsolete = obsolete; this.runtime = runtime;
            this.liquidation = liquidation; this.instrument = instrument; this.assetId = assetId;
            this.positionKey = positionKey; this.userId = liquidation.userId();
            this.laneId = runtime.topology().accountLaneId(userId);
        }

        private static BatchExecutionStage prepare(ExecutionRequest request,
                                                    TradingRuntimeState runtime,
                                                    RuntimeIdentityRegistry identities) {
            return prepare(null, request.command(), request.canceledOrders(), request.nextCursorOrderId(),
                    runtime, identities);
        }

        private static BatchExecutionStage prepare(BatchExecutionStage reuse,
                                                    ExecuteLiquidationCommand command,
                                                    Collection<CoreOrderState> canceledOrders,
                                                    long nextCursorOrderId,
                                                    TradingRuntimeState runtime,
                                                    RuntimeIdentityRegistry identities) {
            if (!runtime.productLine().isDerivative()) {
                throw new IllegalArgumentException("liquidation requires derivative product");
            }
            LiquidationRuntime liquidation = runtime.liquidation(command.liquidationId());
            if (liquidation == null) {
                throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
            }
            boolean advance = nextCursorOrderId != 0;
            if (advance) {
                if (liquidation.status() == CoreLiquidationState.Status.ORDERED
                        && liquidation.nextCancelOrderId() != command.cursorOrderId()) {
                    throw new CoreStateRejectedException("LIQUIDATION_CURSOR_CONFLICT",
                            "liquidation cancellation cursor does not match state");
                }
            } else {
                if (liquidation.status() != CoreLiquidationState.Status.PLANNED
                        && liquidation.status() != CoreLiquidationState.Status.ORDERED) {
                    throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "liquidation is not planned");
                }
                validatePrice(runtime, liquidation, command);
            }
            boolean obsolete = !advance && !executable(runtime, liquidation, identities);
            int count = obsolete || canceledOrders == null ? 0 : canceledOrders.size();
            long[] orderIds = count == 0 ? null : new long[count];
            if (count != 0) {
                int index = 0;
                for (CoreOrderState order : canceledOrders) {
                    if (order == null || order.userId() != liquidation.userId()) {
                        throw new IllegalArgumentException("liquidation cancellation must belong to its account");
                    }
                    orderIds[index++] = order.orderId();
                }
            }
            CoreInstrument instrument = advance || obsolete ? null : requireInstrument(runtime,
                    identities.symbol(liquidation.symbolId()));
            int assetId = instrument == null ? 0 : identities.assetId(instrument.settleAsset());
            long positionKey = instrument == null ? 0 : identities.positionKey(
                    liquidation.userId(), instrument, liquidation.positionSide());
            if (reuse == null) return new BatchExecutionStage(command, orderIds, nextCursorOrderId, advance,
                    obsolete, runtime, liquidation, instrument, assetId, positionKey);
            reuse.reset(command, orderIds, nextCursorOrderId, advance, obsolete, runtime, liquidation,
                    instrument, assetId, positionKey);
            return reuse;
        }

        private RuntimeTreasuryDelta apply() {
            for (int index = 0; index < canceledCount; index++) {
                long orderId = orderIds[index];
                OrderRuntime order = runtime.order(orderId);
                if (order == null || order.userId() != liquidation.userId() || order.canceled()
                        || order.remainingQuantitySteps() == 0) {
                    throw new IllegalArgumentException("runtime liquidation cancellation requires open orders");
                }
                ReservationRuntime reservation = runtime.reservation(orderId);
                if (reservation == null) {
                    throw new IllegalStateException("runtime liquidation reservation is missing: " + orderId);
                }
                runtime.cancelOrder(orderId, liquidation.userId(), reservation.reservedUnits());
            }
            if (obsolete) {
                runtime.replaceLiquidation(copy(liquidation, 0, 0, 0, CoreLiquidationState.Status.CANCELED, 0));
                runtime.advanceUserRevision(liquidation.userId());
                return null;
            }
            if (advance) {
                runtime.replaceLiquidation(new LiquidationRuntime(liquidation.liquidationId(), liquidation.userId(),
                        liquidation.symbolId(), liquidation.marginMode(), liquidation.positionSide(), liquidation.instrument(),
                        liquidation.triggerPriceSequence(),
                        liquidation.signedQuantitySteps(), liquidation.closeQuantitySteps(), liquidation.deficitUnits(),
                        liquidation.executionPriceTicks(), liquidation.liquidationFeeRatePpm(),
                        liquidation.liquidationFeeUnits(), CoreLiquidationState.Status.ORDERED, nextCursorOrderId));
                return null;
            }
            return executeAccountLiquidation(command, runtime, liquidation, instrument, assetId, positionKey);
        }

        private void clearReferences() {
            command = null;
            orderIds = null;
            runtime = null;
            liquidation = null;
            instrument = null;
        }
    }

    private static ExecutionWork execute(ExecutionWork reuse, ExecuteLiquidationCommand command,
            Collection<CoreOrderState> canceledOrders, long nextCursorOrderId,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, boolean asynchronous) {
        if (command == null || runtime == null || identities == null || nextCursorOrderId < 0
                || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation account stage");
        }
        runtime.assertOwner();
        ExecutionWork work = reuse == null ? new ExecutionWork() : reuse;
        work.prepare(command, canceledOrders, nextCursorOrderId, runtime, identities);
        if (asynchronous) {
            runtime.dispatchControlLanes(1L << work.stage.laneId, work);
            return work;
        }
        runtime.releaseOwnerLaneAccess();
        Object[] results = runtime.executeLaneMutations(1L << work.stage.laneId, 1, false, work);
        work.finish(results[work.stage.laneId]);
        return null;
    }

    /** One liquidation account stage with a reusable owner polling continuation. */
    public static final class ExecutionWork
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        private BatchExecutionStage stage;
        private TradingRuntimeState runtime;
        private boolean done;

        private void prepare(ExecuteLiquidationCommand command, Collection<CoreOrderState> canceledOrders,
                long nextCursorOrderId, TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
            this.stage = BatchExecutionStage.prepare(stage, command, canceledOrders, nextCursorOrderId,
                    runtime, identities);
            this.runtime = runtime;
            this.done = false;
        }

        @Override
        public Object apply(int ignored) { return stage.apply(); }

        @Override
        public boolean getAsBoolean() {
            if (done) return true;
            if (!runtime.pollControlLanes()) return false;
            finish(runtime.controlLaneResult(stage.laneId));
            return true;
        }

        private void finish(Object result) {
            if (done) return;
            finishExecution(runtime, stage.userId, stage.assetId, stage.positionKey,
                    (RuntimeTreasuryDelta) result, stage.canceledCount);
            done = true;
        }

        /** Drop command references while retaining the continuation object for the next slot use. */
        public void clearReferences() {
            if (stage != null) stage.clearReferences();
            runtime = null;
            done = false;
        }
    }

    private static void finishExecution(TradingRuntimeState runtime, long userId, int assetId,
            long positionKey, RuntimeTreasuryDelta treasuryDelta, int canceledCount) {
        if (treasuryDelta != null) {
            runtime.recordUserSettlementChanges(userId, assetId, positionKey);
            treasuryDelta.apply(runtime.treasury());
        }
        runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(), canceledCount == 0 ? 1 : 2));
    }

    private static RuntimeTreasuryDelta executeAccountLiquidation(ExecuteLiquidationCommand command,
            TradingRuntimeState runtime, LiquidationRuntime liquidation, CoreInstrument instrument,
            int settleAssetId, long positionKey) {
        PositionRuntime position = runtime.position(positionKey);
        BalanceRuntime balance = runtime.balance(liquidation.userId(), settleAssetId);
        if (position == null || balance == null) {
            throw new IllegalStateException("runtime liquidation entities are missing");
        }

        long currentAbs = Math.absExact(position.signedQuantitySteps());
        long closeQuantity = liquidation.closeQuantitySteps();
        long remainingAbs = Math.subtractExact(currentAbs, closeQuantity);
        long releasedMargin = position.positionMarginUnits() == 0 ? 0
                : proportional(position.positionMarginUnits(), closeQuantity, currentAbs);
        long signedCloseQuantity = position.signedQuantitySteps() > 0
                ? closeQuantity : Math.negateExact(closeQuantity);
        long pnl = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument, signedCloseQuantity,
                command.executionPriceTicks())
                : CoreContractMath.pnlUnits(instrument, signedCloseQuantity,
                position.entryPriceTicks(), command.executionPriceTicks());
        long feeDue = Math.negateExact(CoreContractMath.feeDeltaUnits(instrument,
                command.executionPriceTicks(), closeQuantity, command.liquidationFeeRatePpm()));
        LiquidationCash cash = applyCash(balance, liquidation.marginMode(), releasedMargin, pnl, feeDue);
        long uncovered = pnl < 0 ? Math.subtractExact(Math.negateExact(pnl),
                Math.negateExact(Math.min(0, cash.appliedPnl()))) : 0;

        long nextQuantity = remainingAbs == 0 ? 0
                : position.signedQuantitySteps() > 0 ? remainingAbs : Math.negateExact(remainingAbs);
        long nextEntryValue = remainingAbs == 0 ? 0
                : proportional(position.entryValueTicks(), remainingAbs, currentAbs);
        PositionRuntime nextPosition = new PositionRuntime(position.userId(), position.symbolId(), position.assetId(),
                position.marginMode(), position.positionSide(), position.instrument(),
                nextQuantity, remainingAbs == 0 ? 0 : position.entryPriceTicks(), nextEntryValue,
                Math.addExact(position.realizedPnlUnits(), instrument.contractType().isOption() ? 0 : pnl),
                Math.subtractExact(position.positionMarginUnits(), releasedMargin));
        long insuranceDelta = Math.addExact(Math.negateExact(cash.appliedPnl()), cash.collectedFee());
        CoreLiquidationState.Status nextStatus = uncovered > 0
                ? CoreLiquidationState.Status.INSURANCE_REQUIRED : CoreLiquidationState.Status.COMPLETED;

        runtime.replaceBalance(cash.balance());
        runtime.replacePosition(positionKey, nextPosition);
        runtime.replaceLiquidation(copy(liquidation, uncovered, command.executionPriceTicks(),
                command.liquidationFeeRatePpm(), nextStatus, cash.collectedFee()));
        runtime.advanceUserRevision(liquidation.userId());
        RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        treasuryDelta.addInsurance(settleAssetId, insuranceDelta);
        treasuryDelta.addDeficit(settleAssetId, uncovered);
        treasuryDelta.addClearing(settleAssetId, uncovered);
        return treasuryDelta;
    }

    public static TradingRuntimeState applyCancellationAdvance(TradingCoreState before,
                                                               ExecuteLiquidationCommand command,
                                                               Collection<CoreOrderState> canceledOrders,
                                                               long nextCursorOrderId,
                                                               TradingRuntimeState runtime,
                                                               RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid liquidation cancellation apply");
        }
        return applyCancellationAdvanceRuntime(command, canceledOrders, nextCursorOrderId, runtime, identities);
    }

    public static TradingRuntimeState applyCancellationAdvanceRuntime(ExecuteLiquidationCommand command,
                                                                      Collection<CoreOrderState> canceledOrders,
                                                                      long nextCursorOrderId,
                                                                      TradingRuntimeState runtime,
                                                                      RuntimeIdentityRegistry identities) {
        if (nextCursorOrderId <= 0) throw new IllegalArgumentException("invalid liquidation cancellation cursor");
        execute(null, command, canceledOrders, nextCursorOrderId, runtime, identities, false);
        return runtime;
    }

    private static LiquidationCash applyCash(BalanceRuntime balance, CoreMarginMode marginMode,
                                             long releasedMargin, long pnl, long feeDue) {
        long available = balance.availableUnits();
        long locked = balance.lockedUnits();
        long appliedPnl;
        long collectedFee;
        if (marginMode == CoreMarginMode.ISOLATED) {
            if (pnl < 0) {
                long consumedMargin = Math.min(releasedMargin, Math.negateExact(pnl));
                long remainingMargin = Math.subtractExact(releasedMargin, consumedMargin);
                locked = Math.subtractExact(locked, releasedMargin);
                available = Math.addExact(available, remainingMargin);
                appliedPnl = Math.negateExact(consumedMargin);
                collectedFee = Math.min(feeDue, remainingMargin);
            } else {
                locked = Math.subtractExact(locked, releasedMargin);
                available = Math.addExact(available, Math.addExact(releasedMargin, pnl));
                appliedPnl = pnl;
                collectedFee = Math.min(feeDue, Math.addExact(releasedMargin, pnl));
            }
            available = Math.subtractExact(available, collectedFee);
        } else {
            locked = Math.subtractExact(locked, releasedMargin);
            available = Math.addExact(available, releasedMargin);
            if (pnl >= 0) {
                available = Math.addExact(available, pnl);
                appliedPnl = pnl;
            } else {
                long debit = Math.min(available, Math.negateExact(pnl));
                available = Math.subtractExact(available, debit);
                appliedPnl = Math.negateExact(debit);
            }
            collectedFee = Math.min(available, feeDue);
            available = Math.subtractExact(available, collectedFee);
        }
        return new LiquidationCash(new BalanceRuntime(balance.userId(), balance.assetId(), available, locked),
                appliedPnl, collectedFee);
    }

    private static boolean executable(TradingRuntimeState runtime, LiquidationRuntime liquidation,
                                      RuntimeIdentityRegistry identities) {
        String symbol = identities.symbol(liquidation.symbolId());
        CoreInstrument instrument = runtime.instrument(symbol);
        if (instrument == null || !CoreRiskPolicy.canLiquidate(
                instrument.contractType(), liquidation.signedQuantitySteps())) return false;
        long positionKey = identities.positionKey(
                liquidation.userId(), instrument, liquidation.positionSide());
        PositionRuntime position = runtime.position(positionKey);
        RiskSnapshotRuntime risk = runtime.riskSnapshot(positionKey);
        return position != null && position.instrument() == liquidation.instrument()
                && position.marginMode() == liquidation.marginMode()
                && position.signedQuantitySteps() == liquidation.signedQuantitySteps()
                && risk != null && risk.priceSequence() == liquidation.triggerPriceSequence()
                && risk.status() == CoreRiskStatus.LIQUIDATION;
    }

    private static void validatePrice(TradingRuntimeState runtime, LiquidationRuntime liquidation,
                                      ExecuteLiquidationCommand command) {
        MarkPriceRuntime mark = runtime.markPrice(liquidation.symbolId());
        if (mark == null || mark.priceSequence() != liquidation.triggerPriceSequence()
                || command.triggerPriceSequence() > 0
                && command.triggerPriceSequence() != liquidation.triggerPriceSequence()
                || command.executionPriceTicks() != mark.markPriceTicks()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "liquidation mark price changed");
        }
    }

    private static CoreInstrument requireInstrument(TradingRuntimeState runtime, String symbol) {
        CoreInstrument instrument = runtime.instrument(symbol);
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        return instrument;
    }

    private static LiquidationRuntime copy(LiquidationRuntime current, long deficit, long priceTicks,
                                           long feeRatePpm, CoreLiquidationState.Status status, long feeUnits) {
        return new LiquidationRuntime(current.liquidationId(), current.userId(), current.symbolId(),
                current.marginMode(), current.positionSide(), current.instrument(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                deficit, priceTicks, feeRatePpm, feeUnits, status, 0);
    }

    private static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }

    private record LiquidationCash(BalanceRuntime balance, long appliedPnl, long collectedFee) {
    }
}
