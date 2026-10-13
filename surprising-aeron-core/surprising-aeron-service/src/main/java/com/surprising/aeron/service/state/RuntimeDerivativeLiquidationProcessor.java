package com.surprising.aeron.service.state;

import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.CancellationOrder;
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
import com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand;

import java.util.Collection;
import java.util.List;

/** 强平账户由自己的Lane修改；收付、保险缺口与强平费交给唯一账本线程。 */
public final class RuntimeDerivativeLiquidationProcessor {

    private RuntimeDerivativeLiquidationProcessor() {
    }



    public static TradingRuntimeState applyExecution(TradingCoreState before,
                                                     ExecuteLiquidationCommand command,
                                                     Collection<CancellationOrder> canceledOrders,
                                                     TradingRuntimeState runtime,
                                                     RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid perpetual liquidation apply");
        }
        return applyExecutionRuntime(command, canceledOrders, runtime, identities);
    }

    public static TradingRuntimeState applyExecutionRuntime(ExecuteLiquidationCommand command,
                                                            Collection<CancellationOrder> canceledOrders,
                                                            TradingRuntimeState runtime,
                                                            RuntimeIdentityRegistry identities) {
        if (runtime.systemLedgerStarted()) throw new IllegalStateException("active liquidation requires its asynchronous continuation");
        execute(null, command, canceledOrders, 0, runtime, identities, false);
        return runtime;
    }

    /** Re-arm a command-slot execution continuation while retaining its validation buffers. */
    public static ExecutionWork beginExecution(ExecutionWork reuse, ExecuteLiquidationCommand command,
            Collection<CancellationOrder> canceledOrders, long nextCursorOrderId,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
        return execute(reuse, command, canceledOrders, nextCursorOrderId, runtime, identities, true);
    }

    /**
     * 强平前置校验随命令槽复用，只保存本次不可变输入和每个动作的结果字节。
     * Owner准备身份键；所属Lane读取自身持仓/风险。结果由既有控制完成位发布，
     * 校验完成前不得向Matcher撤单，槽位回收时释放输入，不维护第二份账户状态。
     */
    public static final class ExecutionCheck implements java.util.function.IntFunction<Object> {
        private static final byte CHECK_LANE = 0, EXECUTABLE = 1, OBSOLETE = 2, STALE_PRICE = 3;
        private LiquidationRuntime[] liquidations = new LiquidationRuntime[4];
        private CoreInstrument[] instruments = new CoreInstrument[4];
        private long[] positionKeys = new long[4];
        private int[] laneIds = new int[4];
        private long[] liquidationIds = new long[4], priceSequences = new long[4], prices = new long[4], cursors = new long[4];
        private RuntimeIdentityRegistry identities;
        private byte[] results = new byte[4];
        private TradingRuntimeState runtime;
        private int count;
        private long laneMask;
        private boolean dispatched, done;

        public void prepare(ExecuteLiquidationCommand command, TradingRuntimeState runtime,
                            RuntimeIdentityRegistry identities) {
            initialize(runtime, 1);
            prepareAction(0, command.liquidationId(), command.triggerPriceSequence(),
                    command.executionPriceTicks(), command.cursorOrderId(), identities);
        }

        public void prepare(ExecuteLiquidationBatchCommand command, TradingRuntimeState runtime,
                            RuntimeIdentityRegistry identities) {
            initialize(runtime, command.actions().size());
            for (int index = 0; index < count; index++) {
                var action = command.actions().get(index);
                prepareAction(index, action.liquidationId(), action.triggerPriceSequence(),
                        action.executionPriceTicks(), action.cursorOrderId(), identities);
            }
        }

        private void initialize(TradingRuntimeState runtime, int count) {
            runtime.assertOwner();
            if (this.runtime != null) throw new IllegalStateException("liquidation checks already prepared");
            if (count > results.length) {
                int capacity = Math.max(count, Math.multiplyExact(results.length, 2));
                liquidations = new LiquidationRuntime[capacity]; instruments = new CoreInstrument[capacity];
                positionKeys = new long[capacity]; laneIds = new int[capacity]; results = new byte[capacity];
                liquidationIds = new long[capacity]; priceSequences = new long[capacity]; prices = new long[capacity]; cursors = new long[capacity];
            }
            this.runtime = runtime; this.count = count; laneMask = 0; dispatched = done = false;
        }

        private void prepareAction(int index, long liquidationId, long priceSequence,
                                   long executionPriceTicks, long cursor, RuntimeIdentityRegistry identities) {
            this.identities = identities;
            liquidationIds[index] = liquidationId; priceSequences[index] = priceSequence;
            prices[index] = executionPriceTicks; cursors[index] = cursor;
            results[index] = OBSOLETE; laneIds[index] = -1;
            var route = runtime.liquidationRoute(liquidationId);
            long userId = route == null ? 0 : route.userId();
            if (route == null && !runtime.asynchronousCommands()) {
                var value = runtime.liquidation(liquidationId);
                if (value != null) userId = value.userId();
            }
            if (userId == 0) return;
            laneIds[index] = runtime.topology().accountLaneId(userId);
            results[index] = CHECK_LANE; laneMask |= 1L << laneIds[index];
        }

        public boolean prepared() { return runtime != null; }

        public boolean poll() {
            if (done) return true;
            runtime.assertOwner();
            if (laneMask == 0) { done = true; return true; }
            if (!dispatched) {
                runtime.dispatchControlLanes(laneMask, this);
                dispatched = true;
            }
            if (!runtime.pollControlLanes()) return false;
            done = true;
            return true;
        }

        @Override public Object apply(int laneId) {
            for (int index = 0; index < count; index++) {
                if (laneIds[index] != laneId || results[index] != CHECK_LANE) continue;
                LiquidationRuntime value = runtime.liquidation(liquidationIds[index]);
                liquidations[index] = value;
                if (value == null || (value.status() != CoreLiquidationState.Status.PLANNED
                        && value.status() != CoreLiquidationState.Status.ORDERED)) {
                    results[index] = OBSOLETE; continue;
                }
                if (value.status() == CoreLiquidationState.Status.PLANNED && cursors[index] != 0
                        || value.status() == CoreLiquidationState.Status.ORDERED && cursors[index] != value.nextCancelOrderId())
                    throw new CoreStateRejectedException("LIQUIDATION_CURSOR_CONFLICT", "liquidation cursor does not match state");
                MarkPriceRuntime mark = runtime.markPrice(value.symbolId());
                if (mark == null || mark.priceSequence() != value.triggerPriceSequence()
                        || priceSequences[index] > 0 && priceSequences[index] != value.triggerPriceSequence()
                        || prices[index] != mark.markPriceTicks()) {
                    results[index] = STALE_PRICE; continue;
                }
                CoreInstrument instrument = runtime.instrument(identities.instrumentId(value.symbolId()));
                instruments[index] = instrument;
                if (instrument == null || !CoreRiskPolicy.canLiquidate(instrument.contractType(), value.signedQuantitySteps())) {
                    results[index] = OBSOLETE; continue;
                }
                positionKeys[index] = identities.findPositionKeyValueInLane(runtime.currentLaneCommandScope(), value.userId(), instrument, value.positionSide());
                results[index] = positionKeys[index] != 0 && RuntimeDerivativeLiquidationProcessor.executable(
                        runtime, value, instrument, positionKeys[index]) ? EXECUTABLE : OBSOLETE;
            }
            return null;
        }

        public LiquidationRuntime liquidation(int index) { requireResult(index); return liquidations[index]; }

        /** 批次筛选撤单时过期动作直接跳过，不影响同批次其他账户。 */
        public boolean cancelsOrders(int index) { requireResult(index); return results[index] == EXECUTABLE; }

        public boolean executable(int index) {
            requireResult(index);
            if (results[index] == STALE_PRICE)
                throw new CoreStateRejectedException("STALE_MARK_PRICE", "liquidation mark price changed");
            return results[index] == EXECUTABLE;
        }

        private void requireResult(int index) {
            if (!done || index < 0 || index >= count)
                throw new IllegalStateException("liquidation Lane check is not complete");
        }

        public void clearReferences() {
            java.util.Arrays.fill(liquidations, 0, count, null);
            java.util.Arrays.fill(instruments, 0, count, null);
            runtime = null; identities = null; count = 0; laneMask = 0; dispatched = done = false;
        }
    }

    /** A validated account stage that can be coalesced with other liquidation stages. */
    public record ExecutionRequest(ExecuteLiquidationCommand command,
                                   Collection<CancellationOrder> canceledOrders,
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
        int[] laneIds = new int[requests.size()];
        for (int index = 0; index < requests.size(); index++) {
            var route = runtime.liquidationRoute(requests.get(index).command().liquidationId());
            if (route == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation route is missing");
            laneIds[index] = runtime.topology().accountLaneId(route.userId());
            laneMask |= 1L << laneIds[index];
        }
        RuntimeTreasuryDelta[] results = new RuntimeTreasuryDelta[stages.length];
        runtime.dispatchControlLanes(laneMask, lane -> {
            for (int index = 0; index < stages.length; index++) {
                if (laneIds[index] != lane) continue;
                stages[index] = BatchExecutionStage.prepare(requests.get(index), runtime, identities);
                results[index] = stages[index].apply();
            }
            return results;
        });
        return new BatchFinancialWork(runtime, stages, results);
    }

    /** 已有强平批次的资金交接；账户结果只由对应Lane写一次、账本读一次。 */
    private static final class BatchFinancialWork extends SystemLedgerCommand
            implements java.util.function.BooleanSupplier {
        private final TradingRuntimeState runtime;
        private final BatchExecutionStage[] stages;
        private final RuntimeTreasuryDelta[] results;
        private boolean accountsCollected, ledgerDispatched, done;
        private long revisionDelta;

        private BatchFinancialWork(TradingRuntimeState runtime, BatchExecutionStage[] stages,
                                   RuntimeTreasuryDelta[] results) {
            initializeLedgerCommand(runtime);
            this.runtime = runtime; this.stages = stages; this.results = results;
        }

        @Override public boolean getAsBoolean() {
            if (done) return true;
            if (!accountsCollected) {
                if (!runtime.pollControlLanes()) return false;
                for (int index = 0; index < stages.length; index++) {
                    BatchExecutionStage stage = stages[index];
                    if (results[index] != null)
                        runtime.recordUserSettlementChanges(stage.userId, stage.assetId, stage.positionKey);
                    revisionDelta = Math.addExact(revisionDelta, stage.canceledCount == 0 ? 1 : 2);
                }
                Math.addExact(runtime.revision(), revisionDelta);
                accountsCollected = true;
            }
            if (!ledgerDispatched) {
                if (!runtime.dispatchLedgerCommand(this)) return false;
                ledgerDispatched = true;
            }
            if (!ledgerCommandCompleted()) return false;
            runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(), revisionDelta));
            done = true;
            return true;
        }

        @Override protected void applyLedger(TreasuryRuntime ledger) {
            for (RuntimeTreasuryDelta delta : results) if (delta != null) delta.apply(ledger);
        }
    }

    private static final class BatchExecutionStage {
        private ExecuteLiquidationCommand command;
        private long[] orderIds;
        private long nextCursorOrderId;
        private int canceledCount;
        private boolean advance;
        private TradingRuntimeState runtime;
        private LiquidationRuntime liquidation;
        private CoreInstrument instrument;
        private int assetId;
        private long positionKey;
        private int laneId;
        private long userId;

        private BatchExecutionStage(ExecuteLiquidationCommand command, long[] orderIds,
                                    long nextCursorOrderId, boolean advance,
                                    TradingRuntimeState runtime, LiquidationRuntime liquidation,
                                    CoreInstrument instrument, int assetId, long positionKey) {
            reset(command, orderIds, nextCursorOrderId, advance, runtime, liquidation,
                    instrument, assetId, positionKey);
        }

        private void reset(ExecuteLiquidationCommand command, long[] orderIds,
                           long nextCursorOrderId, boolean advance,
                           TradingRuntimeState runtime, LiquidationRuntime liquidation,
                           CoreInstrument instrument, int assetId, long positionKey) {
            this.command = command; this.orderIds = orderIds; this.nextCursorOrderId = nextCursorOrderId;
            this.canceledCount = orderIds == null ? 0 : orderIds.length;
            this.advance = advance; this.runtime = runtime;
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
                                                    Collection<CancellationOrder> canceledOrders,
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
            int count = canceledOrders == null ? 0 : canceledOrders.size();
            long[] orderIds = count == 0 ? null : new long[count];
            if (count != 0) {
                int index = 0;
                for (var order : canceledOrders) {
                    if (order == null || order.userId() != liquidation.userId()) {
                        throw new IllegalArgumentException("liquidation cancellation must belong to its account");
                    }
                    orderIds[index++] = order.orderId();
                }
            }
            CoreInstrument instrument = advance ? null : runtime.instrument(identities.instrumentId(liquidation.symbolId()));
            AccountLaneState scoped = runtime.currentLaneCommandScope();
            long positionKey = instrument == null ? 0 : scoped == null
                    ? identities.findPositionKeyValue(liquidation.userId(), instrument, liquidation.positionSide())
                    : identities.findPositionKeyValueInLane(scoped, liquidation.userId(), instrument, liquidation.positionSide());
            PositionRuntime position = scoped == null || positionKey == 0 ? null : scoped.positions.get(positionKey);
            int assetId = instrument == null ? 0 : scoped == null ? identities.assetId(instrument.settleAsset())
                    : position == null ? 0 : position.assetId();
            if (position != null && !identities.asset(assetId).equals(instrument.settleAsset()))
                throw new IllegalStateException("liquidation position settlement asset differs from instrument");
            if (reuse == null) return new BatchExecutionStage(command, orderIds, nextCursorOrderId, advance,
                    runtime, liquidation, instrument, assetId, positionKey);
            reuse.reset(command, orderIds, nextCursorOrderId, advance, runtime, liquidation,
                    instrument, assetId, positionKey);
            return reuse;
        }

        private RuntimeTreasuryDelta apply() {
            boolean obsolete = !advance && !executable(runtime, liquidation, instrument, positionKey);
            if (obsolete) canceledCount = 0;
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
            Collection<CancellationOrder> canceledOrders, long nextCursorOrderId,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, boolean asynchronous) {
        if (command == null || runtime == null || identities == null || nextCursorOrderId < 0
                || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation account stage");
        }
        runtime.assertOwner();
        ExecutionWork work = reuse == null || !reuse.reusable() ? new ExecutionWork() : reuse;
        work.prepare(command, canceledOrders, nextCursorOrderId, runtime, identities);
        if (asynchronous) {
            runtime.dispatchControlLanes(1L << work.laneId, work);
            return work;
        }
        runtime.releaseOwnerLaneAccess();
        Object[] results = runtime.executeLaneMutations(1L << work.laneId, 1, work);
        work.finish(results[work.laneId]);
        return null;
    }

    /** One liquidation account stage with a reusable owner polling continuation. */
    public static final class ExecutionWork extends SystemLedgerCommand
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        private BatchExecutionStage stage;
        private TradingRuntimeState runtime;
        private ExecuteLiquidationCommand command;
        private Collection<CancellationOrder> canceledOrders;
        private long nextCursorOrderId;
        private RuntimeIdentityRegistry identities;
        private int laneId;
        private boolean done, accountsCollected, ledgerDispatched;
        private RuntimeTreasuryDelta treasuryDelta;

        private void prepare(ExecuteLiquidationCommand command, Collection<CancellationOrder> canceledOrders,
                long nextCursorOrderId, TradingRuntimeState runtime, RuntimeIdentityRegistry identities) {
            initializeLedgerCommand(runtime);
            this.command = command; this.canceledOrders = canceledOrders;
            this.nextCursorOrderId = nextCursorOrderId; this.identities = identities;
            var route = runtime.liquidationRoute(command.liquidationId());
            long userId = route == null ? runtime.liquidation(command.liquidationId()).userId() : route.userId();
            laneId = runtime.topology().accountLaneId(userId);
            this.runtime = runtime;
            this.done = accountsCollected = ledgerDispatched = false;
            treasuryDelta = null;
        }

        @Override
        public Object apply(int ignored) {
            stage = BatchExecutionStage.prepare(stage, command, canceledOrders, nextCursorOrderId, runtime, identities);
            return stage.apply();
        }

        @Override
        public boolean getAsBoolean() {
            if (done) return true;
            if (!accountsCollected) {
                if (!runtime.pollControlLanes()) return false;
                treasuryDelta = (RuntimeTreasuryDelta) runtime.controlLaneResult(stage.laneId);
                if (treasuryDelta != null)
                    runtime.recordUserSettlementChanges(stage.userId, stage.assetId, stage.positionKey);
                Math.addExact(runtime.revision(), stage.canceledCount == 0 ? 1 : 2);
                accountsCollected = true;
            }
            if (!ledgerDispatched) {
                if (!runtime.dispatchLedgerCommand(this)) return false;
                ledgerDispatched = true;
            }
            if (!ledgerCommandCompleted()) return false;
            runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(), stage.canceledCount == 0 ? 1 : 2));
            done = true;
            return true;
        }

        @Override protected void applyLedger(TreasuryRuntime ledger) {
            if (treasuryDelta != null) treasuryDelta.apply(ledger);
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
            command = null;
            canceledOrders = null;
            identities = null;
            nextCursorOrderId = 0;
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
                                                               Collection<CancellationOrder> canceledOrders,
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
                                                                      Collection<CancellationOrder> canceledOrders,
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
                                      CoreInstrument instrument, long positionKey) {
        if (instrument == null || !CoreRiskPolicy.canLiquidate(
                instrument.contractType(), liquidation.signedQuantitySteps())) return false;
        if (positionKey == 0) return false;
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
