package com.surprising.aeron.service.state;

import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter.CancellationOrder;
import com.surprising.aeron.service.state.instrument.CoreInstrument;

import com.surprising.aeron.service.business.ProductTradingRules;
import com.surprising.aeron.service.business.ProductTradingRulesRegistry;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;

import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

public final class RuntimeLifecycleSettlementContinuation {

    private RuntimeLifecycleSettlementContinuation() {
    }

    /**
     * Non-blocking lifecycle settlement state machine used by the live Cluster owner.
     * Each poll advances at most one existing ControlLaneDispatcher phase; no owner
     * thread waits for an account lane. The synchronous applyRuntime method on
     * {@link RuntimeLifecycleSettlement} remains the blocking restore/offline path.
     */
    public static SettlementWork prepare(SettleInstrumentCommand command,
                                              Iterable<Long> indexedUserIds, UUID chunkCommandId,
                                              ActiveOrderIndex activeOrderIndex,
                                              TradingRuntimeState runtime,
                                              RuntimeIdentityRegistry identities) {
        return prepare(null, command, indexedUserIds, chunkCommandId, activeOrderIndex, runtime, identities);
    }

    /** Re-arm a slot-owned continuation while retaining its Lane grouping buffers. */
    public static SettlementWork prepare(SettlementWork reuse, SettleInstrumentCommand command,
                                              Iterable<Long> indexedUserIds, UUID chunkCommandId,
                                              ActiveOrderIndex activeOrderIndex,
                                              TradingRuntimeState runtime,
                                              RuntimeIdentityRegistry identities) {
        if (command == null || indexedUserIds == null || chunkCommandId == null
                || activeOrderIndex == null || runtime == null || identities == null) {
            throw new IllegalArgumentException("invalid asynchronous runtime settlement");
        }
        CoreInstrument instrument = RuntimeLifecycleSettlement.requireInstrument(runtime, command);
        int symbolId = identities.symbolId(instrument.instrumentIdValue());
        long previousSettlement = runtime.treasury().lifecycleSettlement(symbolId);
        if (command.settlementId() < previousSettlement) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "settlement id must increase");
        }
        if (command.settlementId() == previousSettlement) {
            CoreSettlementProgressView completed = new CoreSettlementProgressView(
                    command.settlementId(), true, true, 0, 0, 0, 0);
            return reuse == null || !reuse.reusable() ? SettlementWork.completed(runtime, completed)
                    : reuse.resetCompleted(runtime, completed);
        }
        ProductTradingRules kernel = ProductTradingRulesRegistry.forInstrument(instrument);
        kernel.validateLifecycleSettlement(instrument, command);
        TreasuryRuntime.LifecycleProgressRuntime previousProgress = runtime.treasury().lifecycleProgress(symbolId);
        RuntimeLifecycleSettlement.validateProgress(previousProgress, command, true);
        boolean ordersComplete = previousProgress != null && previousProgress.ordersComplete();
        int accountLaneId = previousProgress == null ? 0 : previousProgress.accountLaneId();
        List<CancellationOrder> selectedOrders = List.of();
        boolean moreOrders = false;
        if (!ordersComplete) {
            RuntimeLifecycleSettlement.OrderPage page = RuntimeLifecycleSettlement.selectOrders(runtime, identities, activeOrderIndex, instrument.instrumentId(),
                    accountLaneId, command.cursorOrderId(), command.maxOrders());
            selectedOrders = page.orders();
            moreOrders = !page.complete();
        }
        int assetId = identities.assetId(instrument.settleAsset());
        if (reuse == null || !reuse.reusable()) {
            return new SettlementWork(command, chunkCommandId, runtime,
                    instrument, kernel, symbolId, assetId, previousProgress, selectedOrders,
                    moreOrders, indexedUserIds);
        }
        return reuse.reset(command, chunkCommandId, runtime, instrument, kernel, symbolId, assetId,
                previousProgress, selectedOrders, moreOrders, indexedUserIds);
    }

    /** Bounded asynchronous settlement continuation. Instances are owner-confined. */
    public static final class SettlementWork extends SystemLedgerCommand implements java.util.function.IntFunction<Object> {
        private enum Phase { CANCEL, PREPARE, APPLY, BUDGET_WAIT, APPLY_WAIT, FINAL_WAIT, DONE }
        private enum FinancialPhase { ORDER_PROGRESS, INSURANCE, FINAL }

        private SettleInstrumentCommand command;
        private UUID chunkCommandId;
        private TradingRuntimeState runtime;
        private CoreInstrument instrument;
        private ProductTradingRules kernel;
        private int symbolId, assetId;
        private TreasuryRuntime.LifecycleProgressRuntime previousProgress;
        private List<CancellationOrder> selectedOrders;
        private boolean moreOrders;
        private List<Long> selectedUserIds;
        private Iterable<Long> indexedUserIds;
        private volatile boolean usersReady = true;
        private boolean usersQueued;
        private final SystemLedgerLane.Entry userInput = ignored -> selectAccountUsers();
        private final LongArrayList[] usersByLane;
        private final ArrayList<CancellationOrder>[] ordersByLane;
        private boolean usersComplete;
        private long orderLaneMask;
        private long userLaneMask;
        private Phase phase;
        private Phase dispatchedPhase;
        private Object[] prepared;
        private RuntimeTreasuryDelta treasuryDelta;
        private CoreSettlementProgressView result;
        private FinancialPhase financialPhase;
        private boolean ledgerDispatched, budgetSufficient;
        private long requiredInsurance, nextOrderCursor;
        private RuntimeTreasuryDelta[] financialResults;

        private SettlementWork(SettleInstrumentCommand command, UUID chunkCommandId,
                TradingRuntimeState runtime, CoreInstrument instrument,
                ProductTradingRules kernel, int symbolId, int assetId,
                TreasuryRuntime.LifecycleProgressRuntime previousProgress,
                List<CancellationOrder> selectedOrders, boolean moreOrders, Iterable<Long> indexedUserIds) {
            int laneCount = runtime.topology().accountLaneCount();
            this.usersByLane = new LongArrayList[laneCount];
            for (int lane = 0; lane < laneCount; lane++) usersByLane[lane] = new LongArrayList();
            this.ordersByLane = newLaneOrderGroups(laneCount);
            reset(command, chunkCommandId, runtime, instrument, kernel, symbolId, assetId,
                    previousProgress, selectedOrders, moreOrders, indexedUserIds);
        }

        @SuppressWarnings("unchecked")
        private SettlementWork(TradingRuntimeState runtime, CoreSettlementProgressView result) {
            initializeLedgerCommand(runtime);
            this.command = null; this.chunkCommandId = null;
            this.runtime = runtime; this.instrument = null; this.kernel = null;
            this.symbolId = this.assetId = 0; this.previousProgress = null; this.selectedOrders = List.of();
            this.moreOrders = false; this.selectedUserIds = List.of();
            this.usersByLane = new LongArrayList[runtime.topology().accountLaneCount()];
            for (int lane = 0; lane < usersByLane.length; lane++) usersByLane[lane] = new LongArrayList();
            this.ordersByLane = (ArrayList<CancellationOrder>[]) new ArrayList<?>[runtime.topology().accountLaneCount()];
            this.usersComplete = true;
            this.orderLaneMask = this.userLaneMask = 0; this.phase = Phase.DONE; this.result = result;
            this.dispatchedPhase = null;
        }

        @SuppressWarnings("unchecked")
        private static ArrayList<CancellationOrder>[] newLaneOrderGroups(int laneCount) {
            return (ArrayList<CancellationOrder>[]) new ArrayList<?>[laneCount];
        }

        private SettlementWork reset(SettleInstrumentCommand command, UUID chunkCommandId,
                TradingRuntimeState runtime, CoreInstrument instrument,
                ProductTradingRules kernel, int symbolId, int assetId,
                TreasuryRuntime.LifecycleProgressRuntime previousProgress,
                List<CancellationOrder> selectedOrders, boolean moreOrders, Iterable<Long> indexedUserIds) {
            if (usersQueued && !usersReady) throw new IllegalStateException("Ledger still owns settlement routes");
            initializeLedgerCommand(runtime);
            if (usersByLane.length != runtime.topology().accountLaneCount()) {
                throw new IllegalStateException("settlement continuation lane topology changed");
            }
            this.command = command; this.chunkCommandId = chunkCommandId; this.runtime = runtime;
            this.instrument = instrument; this.kernel = kernel; this.symbolId = symbolId; this.assetId = assetId;
            this.previousProgress = previousProgress; this.selectedOrders = selectedOrders;
            this.moreOrders = moreOrders; this.selectedUserIds = List.of();
            this.indexedUserIds = indexedUserIds;
            usersReady = usersQueued = false;
            this.usersComplete = false;
            for (LongArrayList users : usersByLane) users.clear();
            for (ArrayList<CancellationOrder> orders : ordersByLane) if (orders != null) orders.clear();
            for (CancellationOrder order : selectedOrders) {
                if (order == null) continue;
                int lane = runtime.topology().accountLaneId(order.userId());
                ArrayList<CancellationOrder> group = ordersByLane[lane];
                if (group == null) ordersByLane[lane] = group = new ArrayList<>();
                group.add(order);
            }
            this.orderLaneMask = RuntimeLifecycleSettlement.laneMask(ordersByLane);
            this.userLaneMask = RuntimeLifecycleSettlement.laneMask(usersByLane);
            if (prepared == null || prepared.length != usersByLane.length) prepared = new Object[usersByLane.length];
            else Arrays.fill(prepared, null);
            if (treasuryDelta == null) treasuryDelta = new RuntimeTreasuryDelta();
            else treasuryDelta.clear();
            if (financialResults == null) financialResults = new RuntimeTreasuryDelta[usersByLane.length];
            else Arrays.fill(financialResults, null);
            ledgerDispatched = budgetSufficient = false;
            requiredInsurance = nextOrderCursor = 0;
            financialPhase = null;
            this.result = null;
            this.dispatchedPhase = null;
            this.phase = selectedOrders.isEmpty() ? Phase.PREPARE : Phase.CANCEL;
            if (!runtime.systemLedgerStarted()) selectAccountUsers();
            return this;
        }

        private void selectAccountUsers() {
            RuntimeLifecycleSettlement.UserPage page = moreOrders
                    ? new RuntimeLifecycleSettlement.UserPage(List.of(), 0, 0, true)
                    : RuntimeLifecycleSettlement.selectUsers(indexedUserIds, runtime, 0, command.cursorUserId(), command.maxUsers());
            selectedUserIds = page.userIds(); usersComplete = page.complete();
            for (long user : selectedUserIds) usersByLane[runtime.topology().accountLaneId(user)].add(user);
            userLaneMask = RuntimeLifecycleSettlement.laneMask(usersByLane);
            indexedUserIds = null;
            TradingRuntimeState completion = runtime;
            usersReady = true;
            completion.signalOwnerCompletion();
        }

        /** IDs of the same bounded page consumed by Account Lanes; no second index traversal. */
        public List<Long> selectedUsers() {
            if (!usersReady) throw new IllegalStateException("Ledger settlement routes are incomplete");
            return selectedUserIds;
        }

        private SettlementWork resetCompleted(TradingRuntimeState runtime, CoreSettlementProgressView result) {
            if (usersQueued && !usersReady) throw new IllegalStateException("Ledger still owns settlement routes");
            initializeLedgerCommand(runtime);
            this.command = null; this.chunkCommandId = null; this.runtime = runtime;
            this.instrument = null; this.kernel = null; this.symbolId = this.assetId = 0;
            this.previousProgress = null; this.selectedOrders = List.of(); this.selectedUserIds = List.of();
            this.moreOrders = false; this.usersComplete = true; this.orderLaneMask = this.userLaneMask = 0;
            indexedUserIds = null; usersReady = true; usersQueued = false;
            for (LongArrayList users : usersByLane) users.clear();
            for (ArrayList<CancellationOrder> orders : ordersByLane) if (orders != null) orders.clear();
            if (prepared != null) Arrays.fill(prepared, null);
            if (treasuryDelta != null) treasuryDelta.clear();
            this.result = result; this.phase = Phase.DONE;
            this.dispatchedPhase = null;
            return this;
        }

        /** Drop command/page references before the fixed command slot is recycled. */
        public void clearReferences() {
            if (usersQueued && !usersReady) return;
            indexedUserIds = null;
            command = null; chunkCommandId = null; instrument = null; kernel = null;
            previousProgress = null; selectedOrders = List.of(); selectedUserIds = List.of();
            result = null; phase = Phase.DONE;
            dispatchedPhase = null;
            for (LongArrayList users : usersByLane) users.clear();
            for (ArrayList<CancellationOrder> orders : ordersByLane) if (orders != null) orders.clear();
            if (prepared != null) Arrays.fill(prepared, null);
            if (treasuryDelta != null) treasuryDelta.clear();
        }

        static SettlementWork completed(TradingRuntimeState runtime, CoreSettlementProgressView result) {
            return new SettlementWork(runtime, result);
        }

        public boolean poll() {
            runtime.assertOwner();
            if (phase == Phase.DONE) return true;
            if (!usersReady) {
                if (!usersQueued) usersQueued = runtime.dispatchLedgerQuery(userInput);
                if (!usersReady) return false;
            }
            switch (phase) {
                case CANCEL -> {
                    dispatchedPhase = Phase.CANCEL;
                    runtime.dispatchControlLanes(orderLaneMask, this);
                    phase = Phase.PREPARE;
                    return false;
                }
                case PREPARE -> {
                    if (orderLaneMask != 0 && !runtime.pollControlLanes()) return false;
                    if (moreOrders) {
                        nextOrderCursor = selectedOrders.isEmpty() ? command.cursorOrderId() : selectedOrders.getLast().orderId();
                        financialPhase = FinancialPhase.ORDER_PROGRESS;
                        phase = Phase.FINAL_WAIT;
                        return false;
                    }
                    if (userLaneMask == 0) {
                        financialPhase = FinancialPhase.FINAL;
                        phase = Phase.FINAL_WAIT;
                        return false;
                    }
                    dispatchedPhase = Phase.PREPARE;
                    runtime.dispatchControlLanes(userLaneMask, this);
                    phase = Phase.APPLY;
                    return false;
                }
                case APPLY -> {
                    if (!runtime.pollControlLanes()) return false;
                    Arrays.fill(prepared, null);
                    for (int lane = 0; lane < usersByLane.length; lane++)
                        if ((userLaneMask & (1L << lane)) != 0) prepared[lane] = runtime.controlLaneResult(lane);
                    financialPhase = FinancialPhase.INSURANCE;
                    ledgerDispatched = false;
                    phase = Phase.BUDGET_WAIT;
                    return false;
                }
                case BUDGET_WAIT -> {
                    if (!pollLedger()) return false;
                    if (!budgetSufficient) {
                        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
                        long cursor = command.cursorUserId();
                        result = new CoreSettlementProgressView(command.settlementId(), false, true,
                                0, cursor, selectedOrders.size(), 0, requiredInsurance);
                        phase = Phase.DONE;
                        return true;
                    }
                    dispatchedPhase = Phase.APPLY;
                    runtime.dispatchControlLanes(userLaneMask, this);
                    phase = Phase.APPLY_WAIT;
                    return false;
                }
                case APPLY_WAIT -> {
                    if (!runtime.pollControlLanes()) return false;
                    for (int lane = 0; lane < usersByLane.length; lane++)
                        if ((userLaneMask & (1L << lane)) != 0)
                            financialResults[lane] = (RuntimeTreasuryDelta) runtime.controlLaneResult(lane);
                    financialPhase = FinancialPhase.FINAL;
                    ledgerDispatched = false;
                    phase = Phase.FINAL_WAIT;
                    return false;
                }
                case FINAL_WAIT -> {
                    if (!pollLedger()) return false;
                    runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(), selectedOrders.isEmpty() ? 1 : 2));
                    if (financialPhase == FinancialPhase.ORDER_PROGRESS)
                        result = new CoreSettlementProgressView(command.settlementId(), false, false,
                                nextOrderCursor, 0, selectedOrders.size(), 0);
                    else {
                        boolean complete = usersComplete || selectedUserIds.isEmpty();
                        result = new CoreSettlementProgressView(command.settlementId(), complete, true,
                                0, complete ? 0 : selectedUserIds.getLast(), selectedOrders.size(), selectedUserIds.size());
                    }
                    phase = Phase.DONE;
                    return true;
                }
                default -> throw new IllegalStateException("invalid settlement continuation phase");
            }
        }

        /** Reused Lane operation for all three asynchronous settlement phases. */
        @Override
        public Object apply(int lane) {
            return switch (dispatchedPhase) {
                case CANCEL -> {
                    RuntimeLifecycleSettlement.cancelOrdersOnLane(runtime, ordersByLane[lane]);
                    yield null;
                }
                case PREPARE -> RuntimeLifecycleSettlement.prepareLane(runtime, instrument, kernel, command,
                        usersByLane[lane], symbolId, assetId);
                case APPLY -> {
                    @SuppressWarnings("unchecked") List<RuntimeLifecycleSettlement.UserSettlement> plans =
                            (List<RuntimeLifecycleSettlement.UserSettlement>) prepared[lane];
                    yield RuntimeLifecycleSettlement.applyLane(runtime, assetId, plans);
                }
                default -> throw new IllegalStateException("settlement lane operation outside dispatch phase");
            };
        }

        private boolean pollLedger() {
            if (!ledgerDispatched) {
                Math.addExact(runtime.revision(), financialPhase == FinancialPhase.INSURANCE ? 1 : selectedOrders.isEmpty() ? 1 : 2);
                if (!runtime.dispatchLedgerCommand(this)) return false;
                ledgerDispatched = true;
            }
            return ledgerCommandCompleted();
        }

        @Override protected boolean financialScopeComplete() {
            return financialPhase != FinancialPhase.INSURANCE || !budgetSufficient;
        }

        @Override protected void applyLedger(TreasuryRuntime ledger) {
            if (financialPhase == FinancialPhase.ORDER_PROGRESS) {
                ledger.setLifecycleProgress(symbolId, new TreasuryRuntime.LifecycleProgressRuntime(
                        command.settlementId(), instrument, command.settlementPriceTicks(), command.optionCashUnitsPerContract(),
                        false, previousProgress == null ? 0 : previousProgress.accountLaneId(), nextOrderCursor, 0, chunkCommandId));
                return;
            }
            if (financialPhase == FinancialPhase.INSURANCE) {
                requiredInsurance = 0;
                for (Object receipt : prepared) {
                    if (receipt == null) continue;
                    @SuppressWarnings("unchecked") List<RuntimeLifecycleSettlement.UserSettlement> plans =
                            (List<RuntimeLifecycleSettlement.UserSettlement>) receipt;
                    for (RuntimeLifecycleSettlement.UserSettlement plan : plans)
                        requiredInsurance = Math.addExact(requiredInsurance, plan.insurance());
                }
                budgetSufficient = requiredInsurance <= ledger.insurance(assetId);
                if (!budgetSufficient) {
                    long cursor = command.cursorUserId();
                    ledger.setLifecycleProgress(symbolId, new TreasuryRuntime.LifecycleProgressRuntime(
                            command.settlementId(), instrument, command.settlementPriceTicks(), command.optionCashUnitsPerContract(),
                            true, 0, 0, cursor, chunkCommandId, requiredInsurance));
                } else if (requiredInsurance != 0) {
                    ledger.setInsurance(assetId, Math.subtractExact(ledger.insurance(assetId), requiredInsurance),
                            ledger.insuranceDeficit(assetId));
                }
                return;
            }
            treasuryDelta.clear();
            for (RuntimeTreasuryDelta delta : financialResults) if (delta != null) treasuryDelta.merge(delta);
            treasuryDelta.apply(ledger);
            boolean complete = usersComplete || selectedUserIds.isEmpty();
            if (complete) ledger.setLifecycleSettlement(symbolId, command.settlementId());
            else ledger.setLifecycleProgress(symbolId, new TreasuryRuntime.LifecycleProgressRuntime(
                    command.settlementId(), instrument, command.settlementPriceTicks(), command.optionCashUnitsPerContract(), true,
                    previousProgress == null ? 0 : previousProgress.accountLaneId(), 0, selectedUserIds.getLast(), chunkCommandId));
        }

        public CoreSettlementProgressView result() {
            if (result == null) throw new IllegalStateException("settlement has not completed");
            return result;
        }
    }


}
