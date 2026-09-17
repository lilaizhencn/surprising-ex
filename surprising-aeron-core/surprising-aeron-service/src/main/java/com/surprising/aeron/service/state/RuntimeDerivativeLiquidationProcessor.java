package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.math.*;

import com.surprising.aeron.service.state.model.CoreLiquidationState;
import com.surprising.aeron.service.state.model.CoreOrderState;
import com.surprising.aeron.service.state.model.CoreRiskStatus;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.ExecuteAdlCommand;

import java.util.Collection;
import java.util.List;

/** Account mutations run on their Lane; Owner applies the treasury delta after account completion. */
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

    /** One account stage owns cancellation and liquidation; only treasury changes return to Owner. */
    public static java.util.function.BooleanSupplier beginExecution(ExecuteLiquidationCommand command,
            Collection<CoreOrderState> canceledOrders, long nextCursorOrderId,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        return beginExecution(null, command, canceledOrders, nextCursorOrderId, runtime, identities);
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
        private CoreInstrumentState instrument;
        private int assetId;
        private long positionKey;
        private int laneId;
        private long userId;

        private BatchExecutionStage(ExecuteLiquidationCommand command, long[] orderIds,
                                    long nextCursorOrderId, boolean advance, boolean obsolete,
                                    TradingRuntimeState runtime, LiquidationRuntime liquidation,
                                    CoreInstrumentState instrument, int assetId, long positionKey) {
            reset(command, orderIds, nextCursorOrderId, advance, obsolete, runtime, liquidation,
                    instrument, assetId, positionKey);
        }

        private void reset(ExecuteLiquidationCommand command, long[] orderIds,
                           long nextCursorOrderId, boolean advance, boolean obsolete,
                           TradingRuntimeState runtime, LiquidationRuntime liquidation,
                           CoreInstrumentState instrument, int assetId, long positionKey) {
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
            CoreInstrumentState instrument = advance || obsolete ? null : requireInstrument(runtime,
                    identities.symbol(liquidation.symbolId()), liquidation.instrumentChangeId());
            int assetId = instrument == null ? 0 : identities.assetId(instrument.settleAsset());
            long positionKey = instrument == null ? 0 : identities.positionKey(liquidation.userId(),
                    positionKey(identities.symbol(liquidation.symbolId()), liquidation.positionSide()));
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
                        liquidation.symbolId(), liquidation.marginMode(), liquidation.positionSide(),
                        liquidation.instrumentChangeId(), liquidation.triggerPriceSequence(),
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
            TradingRuntimeState runtime, LiquidationRuntime liquidation, CoreInstrumentState instrument,
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
                position.marginMode(), position.positionSide(), remainingAbs == 0 ? 0 : position.instrumentChangeId(),
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

    public static TradingRuntimeState applyResolution(TradingCoreState before,
                                                      ResolveLiquidationCommand command,
                                                      TradingRuntimeState runtime,
                                                      RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid liquidation resolution apply");
        }
        return applyResolutionRuntime(command, runtime, identities, before.riskState().liquidations().keySet());
    }

    public static TradingRuntimeState applyResolutionRuntime(ResolveLiquidationCommand command,
                                                             TradingRuntimeState runtime,
                                                             RuntimeIdentityRegistry identities,
                                                             Iterable<Long> candidateIds) {
        resolve(null, command, runtime, identities, candidateIds, false);
        return runtime;
    }

    public static java.util.function.BooleanSupplier beginResolution(ResolveLiquidationCommand command,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, Iterable<Long> candidateIds) {
        return beginResolution(null, command, runtime, identities, candidateIds);
    }

    /** Re-arm a slot-owned resolution continuation while retaining its treasury delta storage. */
    public static ResolutionWork beginResolution(ResolutionWork reuse,
            ResolveLiquidationCommand command, TradingRuntimeState runtime,
            RuntimeIdentityRegistry identities, Iterable<Long> candidateIds) {
        return resolve(reuse, command, runtime, identities, candidateIds, true);
    }

    private static ResolutionWork resolve(ResolutionWork reuse,
            ResolveLiquidationCommand command,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, Iterable<Long> candidateIds,
            boolean asynchronous) {
        if (command == null || runtime == null || identities == null || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation resolution apply");
        }
        runtime.assertOwner();
        LiquidationRuntime liquidation = runtime.liquidation(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        CoreInstrumentState instrument = requireInstrument(runtime, identities.symbol(liquidation.symbolId()),
                liquidation.instrumentChangeId());
        LiquidationRuntime current = liquidation;
        ResolutionWork work = reuse == null ? new ResolutionWork() : reuse;
        CoreLiquidationState.Status nextStatus;
        long nextDeficit = liquidation.deficitUnits();
        RuntimeTreasuryDelta treasuryDelta = work.delta();
        treasuryDelta.clear();
        switch (command.resolution()) {
            case INSURANCE -> {
                if (liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) {
                    throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT",
                            "insurance resolution requires insurance state");
                }
                if (command.coveredUnits() > liquidation.deficitUnits()) {
                    throw new CoreStateRejectedException("INSURANCE_COVER_EXCEEDS_DEFICIT",
                            "insurance coverage must be within liquidation deficit");
                }
                int assetId = identities.assetId(instrument.settleAsset());
                InsuranceAllocationPolicy.Resolution allocation = InsuranceAllocationPolicy.resolve(
                        runtime, identities, candidateIds, liquidation.liquidationId());
                if (!allocation.next()) {
                    throw new CoreStateRejectedException("INSURANCE_RESOLUTION_ORDER_MISMATCH",
                            "insurance claims must resolve in deterministic priority order");
                }
                long expectedCoverage = allocation.expectedCoverage();
                if (command.coveredUnits() != expectedCoverage) {
                    throw new CoreStateRejectedException("INSURANCE_ALLOCATION_MISMATCH",
                            "insurance coverage does not match deterministic allocation");
                }
                if (command.coveredUnits() > runtime.treasury().insurance(assetId)) {
                    throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE",
                            "insurance fund balance is insufficient");
                }
                if (command.coveredUnits() != 0) {
                    treasuryDelta.addInsurance(assetId, Math.negateExact(command.coveredUnits()));
                    treasuryDelta.addDeficit(assetId, Math.negateExact(command.coveredUnits()));
                }
                nextDeficit = Math.subtractExact(nextDeficit, command.coveredUnits());
                nextStatus = nextDeficit == 0 ? CoreLiquidationState.Status.COMPLETED
                        : CoreLiquidationState.Status.ADL_REQUIRED;
            }
            case ADL -> throw new CoreStateRejectedException("INVALID_COMMAND",
                    "ADL resolution requires atomic target deleveraging");
            case COMPLETED -> {
                if (command.coveredUnits() != 0) {
                    throw new CoreStateRejectedException("INVALID_COMMAND", "completed resolution covers no units");
                }
                if (liquidation.deficitUnits() != 0) {
                    throw new CoreStateRejectedException("LIQUIDATION_DEFICIT_REMAINS",
                            "liquidation deficit must be fully covered before completion");
                }
                nextStatus = CoreLiquidationState.Status.COMPLETED;
            }
            default -> throw new IllegalStateException("unknown liquidation resolution");
        }
        LiquidationRuntime nextLiquidation = new LiquidationRuntime(current.liquidationId(), current.userId(),
                current.symbolId(), current.marginMode(), current.positionSide(), current.instrumentChangeId(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                nextDeficit, current.executionPriceTicks(), current.liquidationFeeRatePpm(),
                current.liquidationFeeUnits(), nextStatus, 0);
        long laneMask = runtime.topology().accountLaneMask(current.userId());
        work.prepare(runtime, nextLiquidation, laneMask);
        if (asynchronous) {
            runtime.dispatchControlLanes(laneMask, work);
            return work;
        }
        runtime.releaseOwnerLaneAccess();
        runtime.executeLaneMutations(laneMask, 1, false, work);
        work.finish();
        return null;
    }

    /** One account-lane mutation plus the owner-side insurance delta for a resolution. */
    public static final class ResolutionWork
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        private final RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        private TradingRuntimeState runtime;
        private LiquidationRuntime nextLiquidation;
        private boolean done;

        private RuntimeTreasuryDelta delta() { return treasuryDelta; }

        private void prepare(TradingRuntimeState runtime, LiquidationRuntime nextLiquidation, long laneMask) {
            this.runtime = runtime;
            this.nextLiquidation = nextLiquidation;
            this.done = false;
        }

        @Override
        public Object apply(int ignored) {
            runtime.replaceLiquidation(nextLiquidation);
            return null;
        }

        @Override
        public boolean getAsBoolean() {
            if (done) return true;
            if (!runtime.pollControlLanes()) return false;
            finish();
            return true;
        }

        private void finish() {
            if (done) return;
            treasuryDelta.apply(runtime.treasury());
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            done = true;
        }
    }



    public static TradingRuntimeState applyAdl(TradingCoreState before, ExecuteAdlCommand command,
                                               TradingRuntimeState runtime,
                                               RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid ADL apply");
        }
        return applyAdlRuntime(command, runtime, identities);
    }

    public static TradingRuntimeState applyAdlRuntime(ExecuteAdlCommand command, TradingRuntimeState runtime,
                                                      RuntimeIdentityRegistry identities) {
        adl(null, command, runtime, identities, false);
        return runtime;
    }

    public static java.util.function.BooleanSupplier beginAdl(ExecuteAdlCommand command,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        return beginAdl(null, command, runtime, identities);
    }

    /** Re-arm a slot-owned ADL continuation while retaining its primitive delta buffer. */
    public static AdlWork beginAdl(AdlWork reuse, ExecuteAdlCommand command,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        return adl(reuse, command, runtime, identities, true);
    }

    private static AdlWork adl(AdlWork reuse, ExecuteAdlCommand command,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, boolean asynchronous) {
        if (command == null || runtime == null || identities == null || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid ADL apply");
        }
        runtime.assertOwner();
        LiquidationRuntime liquidation = runtime.liquidation(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        if (liquidation.status() != CoreLiquidationState.Status.ADL_REQUIRED) {
            throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "ADL requires ADL state");
        }
        if (runtime.treasury().fundingProgress(liquidation.symbolId()) != null) {
            throw new CoreStateRejectedException("LIFECYCLE_IN_PROGRESS", "funding position cut is in progress");
        }
        if (!identities.symbol(liquidation.symbolId()).equals(command.symbol())
                || command.targetUserId() == liquidation.userId()
                || command.coveredUnits() > liquidation.deficitUnits()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "ADL command does not match liquidation");
        }
        CoreInstrumentState instrument = requireInstrument(runtime, identities.symbol(liquidation.symbolId()),
                liquidation.instrumentChangeId());
        MarkPriceRuntime mark = runtime.markPrice(liquidation.symbolId());
        if (mark == null || mark.priceSequence() != command.markPriceSequence()) {
            throw new CoreStateRejectedException("STALE_MARK_PRICE", "ADL mark price changed");
        }

        long positionKey = identities.positionKey(command.targetUserId(),
                positionKey(command.symbol(), command.positionSide()));
        int settleAssetId = identities.assetId(instrument.settleAsset());
        int targetLane = runtime.topology().accountLaneId(command.targetUserId());
        long mask = (1L << targetLane) | runtime.topology().accountLaneMask(liquidation.userId());
        AdlWork work = reuse == null ? new AdlWork() : reuse;
        work.prepare(command, runtime, liquidation, instrument, mark, positionKey,
                settleAssetId, targetLane);
        if (asynchronous) {
            runtime.dispatchControlLanes(mask, work);
            return work;
        }
        runtime.releaseOwnerLaneAccess();
        Object[] results = runtime.executeLaneMutations(mask, Long.bitCount(mask), false, work);
        work.finish(results[targetLane]);
        return null;
    }

    /** Two-account-lane ADL mutation plus its single owner-side treasury delta. */
    public static final class AdlWork
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        private ExecuteAdlCommand command;
        private TradingRuntimeState runtime;
        private LiquidationRuntime liquidation;
        private CoreInstrumentState instrument;
        private MarkPriceRuntime mark;
        private long positionKey;
        private int settleAssetId, targetLane;
        private final RuntimeTreasuryDelta targetDelta = new RuntimeTreasuryDelta();
        private boolean done;

        private void prepare(ExecuteAdlCommand command, TradingRuntimeState runtime,
                LiquidationRuntime liquidation, CoreInstrumentState instrument, MarkPriceRuntime mark,
                long positionKey, int settleAssetId, int targetLane) {
            this.command = command; this.runtime = runtime; this.liquidation = liquidation;
            this.instrument = instrument; this.mark = mark; this.positionKey = positionKey;
            this.settleAssetId = settleAssetId; this.targetLane = targetLane;
            this.targetDelta.clear(); this.done = false;
        }

        @Override
        public Object apply(int ignored) {
            RuntimeTreasuryDelta delta = null;
            if (runtime.currentLaneOwns(command.targetUserId())) {
                delta = applyAdlTarget(command, runtime, liquidation, instrument, mark,
                        positionKey, settleAssetId, targetDelta);
            }
            if (runtime.currentLaneOwns(liquidation.userId())) completeAdlLiquidation(command, runtime);
            return delta;
        }

        @Override
        public boolean getAsBoolean() {
            if (done) return true;
            if (!runtime.pollControlLanes()) return false;
            finish(runtime.controlLaneResult(targetLane));
            return true;
        }

        private void finish(Object result) {
            if (done) return;
            runtime.recordUserSettlementChanges(command.targetUserId(), settleAssetId, positionKey);
            if (result instanceof RuntimeTreasuryDelta delta) delta.apply(runtime.treasury());
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            done = true;
        }
    }

    private static RuntimeTreasuryDelta applyAdlTarget(ExecuteAdlCommand command, TradingRuntimeState runtime,
            LiquidationRuntime liquidation, CoreInstrumentState instrument, MarkPriceRuntime mark,
            long positionKey, int settleAssetId, RuntimeTreasuryDelta reuse) {
        PositionRuntime position = runtime.position(positionKey);
        if (position == null || position.marginMode() != command.marginMode()
                || position.signedQuantitySteps() != command.expectedSignedQuantitySteps()
                || position.entryPriceTicks() != command.expectedEntryPriceTicks()
                || Long.signum(position.signedQuantitySteps()) == Long.signum(liquidation.signedQuantitySteps())) {
            throw new CoreStateRejectedException("ADL_POSITION_CONFLICT", "ADL target position changed");
        }
        long totalProfit = CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                position.entryPriceTicks(), mark.markPriceTicks());
        long currentAbs = Math.absExact(position.signedQuantitySteps());
        long coverCapacity = totalProfit <= 0 ? 0
                : proportional(totalProfit, command.closeQuantitySteps(), currentAbs);
        if (coverCapacity < command.coveredUnits()) {
            throw new CoreStateRejectedException("ADL_PROFIT_INSUFFICIENT", "ADL target profit is insufficient");
        }
        long remainingAbs = Math.subtractExact(currentAbs, command.closeQuantitySteps());
        long nextQuantity = remainingAbs == 0 ? 0
                : position.signedQuantitySteps() > 0 ? remainingAbs : Math.negateExact(remainingAbs);
        long releasedMargin = proportional(position.positionMarginUnits(), command.closeQuantitySteps(), currentAbs);
        BalanceRuntime balance = runtime.balance(command.targetUserId(), settleAssetId);
        if (balance == null) {
            throw new CoreStateRejectedException("BALANCE_NOT_FOUND", "required balance is missing");
        }
        long closeCashDelta = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument,
                position.signedQuantitySteps() > 0 ? command.closeQuantitySteps()
                        : Math.negateExact(command.closeQuantitySteps()), mark.markPriceTicks())
                : coverCapacity;
        long targetCashDelta = Math.subtractExact(closeCashDelta, command.coveredUnits());
        RuntimeTreasuryDelta treasuryDelta = reuse == null ? new RuntimeTreasuryDelta() : reuse;
        treasuryDelta.clear();
        treasuryDelta.addClearing(settleAssetId, Math.negateExact(targetCashDelta));
        treasuryDelta.addDeficit(settleAssetId, Math.negateExact(command.coveredUnits()));
        treasuryDelta.addClearing(settleAssetId, Math.negateExact(command.coveredUnits()));
        BalanceRuntime nextBalance = new BalanceRuntime(balance.userId(), balance.assetId(),
                Math.addExact(balance.availableUnits(), Math.addExact(releasedMargin, targetCashDelta)),
                Math.subtractExact(balance.lockedUnits(), releasedMargin));
        long nextEntryValue = remainingAbs == 0 ? 0
                : proportional(position.entryValueTicks(), remainingAbs, currentAbs);
        PositionRuntime nextPosition = new PositionRuntime(position.userId(), position.symbolId(), position.assetId(),
                position.marginMode(), position.positionSide(), remainingAbs == 0 ? 0 : position.instrumentChangeId(),
                nextQuantity, remainingAbs == 0 ? 0 : position.entryPriceTicks(), nextEntryValue,
                Math.addExact(position.realizedPnlUnits(),
                        instrument.contractType().isOption() ? 0 : coverCapacity),
                Math.subtractExact(position.positionMarginUnits(), releasedMargin));
        runtime.replaceBalance(nextBalance);
        runtime.replacePosition(positionKey, nextPosition);
        runtime.advanceUserRevision(command.targetUserId());
        return treasuryDelta;
    }

    private static void completeAdlLiquidation(ExecuteAdlCommand command, TradingRuntimeState runtime) {
        LiquidationRuntime current = runtime.liquidation(command.liquidationId());
        long nextDeficit = Math.subtractExact(current.deficitUnits(), command.coveredUnits());
        CoreLiquidationState.Status nextStatus = nextDeficit == 0
                ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED;

        LiquidationRuntime nextLiquidation = new LiquidationRuntime(current.liquidationId(), current.userId(),
                current.symbolId(), current.marginMode(), current.positionSide(), current.instrumentChangeId(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                nextDeficit, current.executionPriceTicks(), current.liquidationFeeRatePpm(),
                current.liquidationFeeUnits(), nextStatus, 0);
        runtime.replaceLiquidation(nextLiquidation);
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
        CoreInstrumentState instrument = runtime.instrument(symbol);
        if (instrument == null || !CoreRiskPolicy.canLiquidate(
                instrument.contractType(), liquidation.signedQuantitySteps())) return false;
        long positionKey = identities.positionKey(liquidation.userId(),
                positionKey(symbol, liquidation.positionSide()));
        PositionRuntime position = runtime.position(positionKey);
        RiskSnapshotRuntime risk = runtime.riskSnapshot(positionKey);
        return position != null && position.instrumentChangeId() == liquidation.instrumentChangeId()
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

    private static CoreInstrumentState requireInstrument(TradingRuntimeState runtime, String symbol, long version) {
        CoreInstrumentState instrument = runtime.instrument(symbol);
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        if (instrument.changeId() != version) {
            throw new CoreStateRejectedException("INSTRUMENT_CHANGE_ID_CONFLICT", "instrument version differs");
        }
        return instrument;
    }

    private static LiquidationRuntime copy(LiquidationRuntime current, long deficit, long priceTicks,
                                           long feeRatePpm, CoreLiquidationState.Status status, long feeUnits) {
        return new LiquidationRuntime(current.liquidationId(), current.userId(), current.symbolId(),
                current.marginMode(), current.positionSide(), current.instrumentChangeId(),
                current.triggerPriceSequence(), current.signedQuantitySteps(), current.closeQuantitySteps(),
                deficit, priceTicks, feeRatePpm, feeUnits, status, 0);
    }

    private static String positionKey(String symbol, CorePositionSide side) {
        return side == CorePositionSide.NET ? symbol : symbol + ':' + side.name();
    }

    private static long proportional(long units, long part, long total) {
        return part == total ? units : Math.multiplyExact(units, part) / total;
    }

    private record LiquidationCash(BalanceRuntime balance, long appliedPnl, long collectedFee) {
    }
}
