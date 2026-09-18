package com.surprising.aeron.service.state;
import com.surprising.aeron.service.state.instrument.CoreInstrumentState;

import com.surprising.aeron.service.business.ProductTradingRules;
import com.surprising.aeron.service.business.ProductTradingRulesRegistry;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.index.ActiveOrderIndex;
import com.surprising.aeron.service.state.model.CoreOrderState;
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
        CoreInstrumentState instrument = RuntimeLifecycleSettlement.requireInstrument(runtime, command);
        int symbolId = identities.symbolId(instrument.symbol());
        long previousSettlement = runtime.treasury().lifecycleSettlement(symbolId);
        if (command.settlementId() < previousSettlement) {
            throw new CoreStateRejectedException("STALE_SETTLEMENT_ID", "settlement id must increase");
        }
        if (command.settlementId() == previousSettlement) {
            CoreSettlementProgressView completed = new CoreSettlementProgressView(
                    command.settlementId(), true, true, 0, 0, 0, 0);
            return reuse == null ? SettlementWork.completed(runtime, completed)
                    : reuse.resetCompleted(runtime, completed);
        }
        ProductTradingRules kernel = ProductTradingRulesRegistry.forInstrument(instrument);
        kernel.validateLifecycleSettlement(instrument, command);
        TreasuryRuntime.LifecycleProgressRuntime previousProgress = runtime.treasury().lifecycleProgress(symbolId);
        RuntimeLifecycleSettlement.validateProgress(previousProgress, command, true);
        boolean ordersComplete = previousProgress != null && previousProgress.ordersComplete();
        int accountLaneId = previousProgress == null ? 0 : previousProgress.accountLaneId();
        List<CoreOrderState> selectedOrders = List.of();
        boolean moreOrders = false;
        if (!ordersComplete) {
            RuntimeLifecycleSettlement.OrderPage page = RuntimeLifecycleSettlement.selectOrders(runtime, identities, activeOrderIndex, instrument.symbol(),
                    accountLaneId, command.cursorOrderId(), command.maxOrders());
            selectedOrders = page.orders();
            moreOrders = !page.complete();
        }
        int assetId = identities.assetId(instrument.settleAsset());
        RuntimeLifecycleSettlement.UserPage userPage = ordersComplete || !moreOrders
                ? RuntimeLifecycleSettlement.selectUsers(indexedUserIds, runtime, accountLaneId, command.cursorUserId(), command.maxUsers())
                : new RuntimeLifecycleSettlement.UserPage(List.of(), accountLaneId, 0, true);
        if (reuse == null) {
            return new SettlementWork(command, chunkCommandId, runtime,
                    instrument, kernel, symbolId, assetId, previousProgress, selectedOrders,
                    moreOrders, userPage);
        }
        return reuse.reset(command, chunkCommandId, runtime, instrument, kernel, symbolId, assetId,
                previousProgress, selectedOrders, moreOrders, userPage);
    }

    /** Bounded asynchronous settlement continuation. Instances are owner-confined. */
    public static final class SettlementWork implements java.util.function.IntFunction<Object> {
        private enum Phase { CANCEL, PREPARE, APPLY, APPLY_WAIT, DONE }

        private SettleInstrumentCommand command;
        private UUID chunkCommandId;
        private TradingRuntimeState runtime;
        private CoreInstrumentState instrument;
        private ProductTradingRules kernel;
        private int symbolId, assetId;
        private TreasuryRuntime.LifecycleProgressRuntime previousProgress;
        private List<CoreOrderState> selectedOrders;
        private boolean moreOrders;
        private List<Long> selectedUserIds;
        private final LongArrayList[] usersByLane;
        private final ArrayList<CoreOrderState>[] ordersByLane;
        private boolean usersComplete;
        private long orderLaneMask;
        private long userLaneMask;
        private Phase phase;
        private Phase dispatchedPhase;
        private Object[] prepared;
        private RuntimeTreasuryDelta treasuryDelta;
        private CoreSettlementProgressView result;

        private SettlementWork(SettleInstrumentCommand command, UUID chunkCommandId,
                TradingRuntimeState runtime, CoreInstrumentState instrument,
                ProductTradingRules kernel, int symbolId, int assetId,
                TreasuryRuntime.LifecycleProgressRuntime previousProgress,
                List<CoreOrderState> selectedOrders, boolean moreOrders, RuntimeLifecycleSettlement.UserPage userPage) {
            int laneCount = runtime.topology().accountLaneCount();
            this.usersByLane = new LongArrayList[laneCount];
            for (int lane = 0; lane < laneCount; lane++) usersByLane[lane] = new LongArrayList();
            this.ordersByLane = newLaneOrderGroups(laneCount);
            reset(command, chunkCommandId, runtime, instrument, kernel, symbolId, assetId,
                    previousProgress, selectedOrders, moreOrders, userPage);
        }

        @SuppressWarnings("unchecked")
        private SettlementWork(TradingRuntimeState runtime, CoreSettlementProgressView result) {
            this.command = null; this.chunkCommandId = null;
            this.runtime = runtime; this.instrument = null; this.kernel = null;
            this.symbolId = this.assetId = 0; this.previousProgress = null; this.selectedOrders = List.of();
            this.moreOrders = false; this.selectedUserIds = List.of();
            this.usersByLane = new LongArrayList[runtime.topology().accountLaneCount()];
            for (int lane = 0; lane < usersByLane.length; lane++) usersByLane[lane] = new LongArrayList();
            this.ordersByLane = (ArrayList<CoreOrderState>[]) new ArrayList<?>[runtime.topology().accountLaneCount()];
            this.usersComplete = true;
            this.orderLaneMask = this.userLaneMask = 0; this.phase = Phase.DONE; this.result = result;
            this.dispatchedPhase = null;
        }

        @SuppressWarnings("unchecked")
        private static ArrayList<CoreOrderState>[] newLaneOrderGroups(int laneCount) {
            return (ArrayList<CoreOrderState>[]) new ArrayList<?>[laneCount];
        }

        private SettlementWork reset(SettleInstrumentCommand command, UUID chunkCommandId,
                TradingRuntimeState runtime, CoreInstrumentState instrument,
                ProductTradingRules kernel, int symbolId, int assetId,
                TreasuryRuntime.LifecycleProgressRuntime previousProgress,
                List<CoreOrderState> selectedOrders, boolean moreOrders, RuntimeLifecycleSettlement.UserPage userPage) {
            if (usersByLane.length != runtime.topology().accountLaneCount()) {
                throw new IllegalStateException("settlement continuation lane topology changed");
            }
            this.command = command; this.chunkCommandId = chunkCommandId; this.runtime = runtime;
            this.instrument = instrument; this.kernel = kernel; this.symbolId = symbolId; this.assetId = assetId;
            this.previousProgress = previousProgress; this.selectedOrders = selectedOrders;
            this.moreOrders = moreOrders; this.selectedUserIds = userPage.userIds();
            this.usersComplete = userPage.complete();
            for (LongArrayList users : usersByLane) users.clear();
            for (ArrayList<CoreOrderState> orders : ordersByLane) if (orders != null) orders.clear();
            for (int index = 0; index < selectedUserIds.size(); index++) {
                long userId = selectedUserIds.get(index);
                usersByLane[runtime.topology().accountLaneId(userId)].add(userId);
            }
            for (CoreOrderState order : selectedOrders) {
                if (order == null) continue;
                int lane = runtime.topology().accountLaneId(order.userId());
                ArrayList<CoreOrderState> group = ordersByLane[lane];
                if (group == null) ordersByLane[lane] = group = new ArrayList<>();
                group.add(order);
            }
            this.orderLaneMask = RuntimeLifecycleSettlement.laneMask(ordersByLane);
            this.userLaneMask = RuntimeLifecycleSettlement.laneMask(usersByLane);
            if (prepared == null || prepared.length != usersByLane.length) prepared = new Object[usersByLane.length];
            else Arrays.fill(prepared, null);
            if (treasuryDelta == null) treasuryDelta = new RuntimeTreasuryDelta();
            else treasuryDelta.clear();
            this.result = null;
            this.dispatchedPhase = null;
            this.phase = selectedOrders.isEmpty() ? Phase.PREPARE : Phase.CANCEL;
            return this;
        }

        private SettlementWork resetCompleted(TradingRuntimeState runtime, CoreSettlementProgressView result) {
            this.command = null; this.chunkCommandId = null; this.runtime = runtime;
            this.instrument = null; this.kernel = null; this.symbolId = this.assetId = 0;
            this.previousProgress = null; this.selectedOrders = List.of(); this.selectedUserIds = List.of();
            this.moreOrders = false; this.usersComplete = true; this.orderLaneMask = this.userLaneMask = 0;
            for (LongArrayList users : usersByLane) users.clear();
            for (ArrayList<CoreOrderState> orders : ordersByLane) if (orders != null) orders.clear();
            if (prepared != null) Arrays.fill(prepared, null);
            if (treasuryDelta != null) treasuryDelta.clear();
            this.result = result; this.phase = Phase.DONE;
            this.dispatchedPhase = null;
            return this;
        }

        /** Drop command/page references before the fixed command slot is recycled. */
        public void clearReferences() {
            command = null; chunkCommandId = null; instrument = null; kernel = null;
            previousProgress = null; selectedOrders = List.of(); selectedUserIds = List.of();
            result = null; phase = Phase.DONE;
            dispatchedPhase = null;
            for (LongArrayList users : usersByLane) users.clear();
            for (ArrayList<CoreOrderState> orders : ordersByLane) if (orders != null) orders.clear();
            if (prepared != null) Arrays.fill(prepared, null);
            if (treasuryDelta != null) treasuryDelta.clear();
        }

        static SettlementWork completed(TradingRuntimeState runtime, CoreSettlementProgressView result) {
            return new SettlementWork(runtime, result);
        }

        public boolean poll() {
            runtime.assertOwner();
            if (phase == Phase.DONE) return true;
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
                        long nextCursor = selectedOrders.isEmpty() ? command.cursorOrderId()
                                : selectedOrders.getLast().orderId();
                        runtime.treasury().setLifecycleProgress(symbolId,
                                new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(),
                                        command.instrumentChangeId(), command.settlementPriceTicks(),
                                        command.optionCashUnitsPerContract(), false,
                                        previousProgress == null ? 0 : previousProgress.accountLaneId(),
                                        nextCursor, 0, chunkCommandId));
                        runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(),
                                selectedOrders.isEmpty() ? 1 : 2));
                        result = new CoreSettlementProgressView(command.settlementId(), false, false,
                                nextCursor, 0, selectedOrders.size(), 0);
                        phase = Phase.DONE;
                        return true;
                    }
                    if (userLaneMask == 0) {
                        finishWithoutUsers();
                        return true;
                    }
                    dispatchedPhase = Phase.PREPARE;
                    runtime.dispatchControlLanes(userLaneMask, this);
                    phase = Phase.APPLY;
                    return false;
                }
                case APPLY -> {
                    if (!runtime.pollControlLanes()) return false;
                    int laneCount = runtime.topology().accountLaneCount();
                    Arrays.fill(prepared, null);
                    long requiredInsurance = 0;
                    for (int lane = 0; lane < laneCount; lane++) {
                        if ((userLaneMask & (1L << lane)) == 0) continue;
                        prepared[lane] = runtime.controlLaneResult(lane);
                        @SuppressWarnings("unchecked") List<RuntimeLifecycleSettlement.UserSettlement> plans = (List<RuntimeLifecycleSettlement.UserSettlement>) prepared[lane];
                        for (RuntimeLifecycleSettlement.UserSettlement plan : plans) requiredInsurance = Math.addExact(requiredInsurance, plan.insurance());
                    }
                    if (requiredInsurance > runtime.treasury().insurance(assetId)) {
                        long cursor = selectedUserIds.isEmpty() ? command.cursorUserId() : selectedUserIds.getLast();
                        UUID progressId = chunkCommandId;
                        runtime.treasury().setLifecycleProgress(symbolId,
                                new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(),
                                        command.instrumentChangeId(), command.settlementPriceTicks(),
                                        command.optionCashUnitsPerContract(), true, 0, 0, cursor,
                                        progressId, requiredInsurance));
                        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
                        result = new CoreSettlementProgressView(command.settlementId(), false, true,
                                0, cursor, selectedOrders.size(), selectedUserIds.size(), requiredInsurance);
                        phase = Phase.DONE;
                        return true;
                    }
                    if (requiredInsurance != 0) runtime.treasury().setInsurance(assetId,
                            Math.subtractExact(runtime.treasury().insurance(assetId), requiredInsurance),
                            runtime.treasury().insuranceDeficit(assetId));
                    dispatchedPhase = Phase.APPLY;
                    runtime.dispatchControlLanes(userLaneMask, this);
                    phase = Phase.APPLY_WAIT;
                    return false;
                }
                case APPLY_WAIT -> {
                    if (!runtime.pollControlLanes()) return false;
                    treasuryDelta.clear();
                    int laneCount = runtime.topology().accountLaneCount();
                    for (int lane = 0; lane < laneCount; lane++) {
                        if ((userLaneMask & (1L << lane)) == 0) continue;
                        treasuryDelta.merge((RuntimeTreasuryDelta) runtime.controlLaneResult(lane));
                    }
                    treasuryDelta.apply(runtime.treasury());
                    boolean complete = usersComplete || selectedUserIds.isEmpty();
                    long nextCursor = complete ? 0 : selectedUserIds.getLast();
                    if (complete) runtime.treasury().setLifecycleSettlement(symbolId, command.settlementId());
                    else runtime.treasury().setLifecycleProgress(symbolId,
                            new TreasuryRuntime.LifecycleProgressRuntime(command.settlementId(),
                                    command.instrumentChangeId(), command.settlementPriceTicks(),
                                    command.optionCashUnitsPerContract(), true,
                                    previousProgress == null ? 0 : previousProgress.accountLaneId(),
                                    0, nextCursor, chunkCommandId));
                    runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(),
                            selectedOrders.isEmpty() ? 1 : 2));
                    result = new CoreSettlementProgressView(command.settlementId(), complete, true,
                            0, nextCursor, selectedOrders.size(), selectedUserIds.size());
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

        private void finishWithoutUsers() {
            runtime.treasury().setLifecycleSettlement(symbolId, command.settlementId());
            runtime.setMetadata(runtime.productLine(), Math.addExact(runtime.revision(),
                    selectedOrders.isEmpty() ? 1 : 2));
            result = new CoreSettlementProgressView(command.settlementId(), true, true, 0, 0,
                    selectedOrders.size(), 0);
            phase = Phase.DONE;
        }

        public CoreSettlementProgressView result() {
            if (result == null) throw new IllegalStateException("settlement has not completed");
            return result;
        }
    }


}
