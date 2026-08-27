package com.surprising.aeron.service;

import com.surprising.aeron.service.matching.DeterministicExchangeCoreAdapter;
import com.surprising.aeron.service.matching.MatcherSnapshot;
import com.surprising.aeron.service.state.OpenInterestIndex;
import com.surprising.aeron.service.state.AlgoOrderIndex;
import com.surprising.aeron.service.state.LiquidationIndex;
import com.surprising.aeron.service.state.CancelAllAfterIndex;
import com.surprising.aeron.service.state.ActiveOrderIndex;
import com.surprising.aeron.service.state.AdlPositionIndex;
import com.surprising.aeron.service.state.PositionUserIndex;
import com.surprising.aeron.service.state.RiskSnapshotIndex;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.RuntimeStateMaterializer;
import com.surprising.aeron.service.state.RuntimeCommitEntry;
import com.surprising.aeron.service.state.RuntimeStateProjector;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.LaneTopology;
import com.surprising.aeron.service.state.AccountLaneView;
import com.surprising.aeron.service.state.TriggerOrderIndex;
import com.surprising.product.api.ProductLine;
import java.util.concurrent.CompletableFuture;

public final class TradingCoreRuntime implements AutoCloseable {
    private final ProductLine productLine;
    private final LaneTopology topology;
    private final DeterministicExchangeCoreAdapter matcher;
    private final PositionUserIndex positionUsers;
    private final OpenInterestIndex openInterest;
    private final TriggerOrderIndex triggers;
    private final AlgoOrderIndex algos;
    private final LiquidationIndex liquidations;
    private final CancelAllAfterIndex timers;
    private final ActiveOrderIndex activeOrders;
    private final AdlPositionIndex adlPositions;
    private final RiskSnapshotIndex riskSnapshots;
    private RuntimeIdentityRegistry identities;
    private TradingRuntimeState runtimeState;
    private long committedRevision;
    private long committedBusinessStateHash;
    private long committedCoreSequence;
    private final CompletableFuture<Void> matcherReady;
    private Thread owner;

    public TradingCoreRuntime(ProductLine productLine, TradingCoreState initialState) {
        this(productLine, initialState, 0, null);
    }

    public TradingCoreRuntime(
            ProductLine productLine,
            TradingCoreState initialState,
            long coreSequence,
            MatcherSnapshot matcherSnapshot) {
        if (productLine == null || initialState == null || initialState.productLine() != productLine) {
            throw new IllegalArgumentException("invalid trading runtime state");
        }
        this.productLine = productLine;
        this.topology = matcherSnapshot == null
                ? LaneTopology.configured(Boolean.getBoolean("surprising.aeron.p10-characterization"))
                : matcherSnapshot.topology();
        this.identities = new RuntimeIdentityRegistry();
        this.runtimeState = RuntimeStateProjector.project(initialState, identities, topology);
        this.committedRevision = initialState.revision();
        this.committedBusinessStateHash = initialState.businessStateHash();
        this.committedCoreSequence = coreSequence;
        this.activeOrders = new ActiveOrderIndex(initialState);
        this.matcher = matcherSnapshot == null
                ? new DeterministicExchangeCoreAdapter()
                : new DeterministicExchangeCoreAdapter(
                        initialState, activeOrders.orders(), coreSequence, matcherSnapshot);
        this.positionUsers = new PositionUserIndex(initialState);
        this.openInterest = new OpenInterestIndex(initialState);
        this.triggers = new TriggerOrderIndex(initialState);
        this.algos = new AlgoOrderIndex(initialState);
        this.liquidations = new LiquidationIndex(initialState);
        this.timers = new CancelAllAfterIndex(initialState);
        this.adlPositions = new AdlPositionIndex(initialState);
        this.riskSnapshots = new RiskSnapshotIndex(initialState);
        this.matcherReady = CompletableFuture.completedFuture(null);
    }

    public void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) {
            owner = current;
            runtimeState.bindOwner();
            identities.assertOwner();
            runtimeState.startAccountLanes();
        } else if (owner != current) {
            throw new IllegalStateException("trading runtime is bound to another thread");
        }
    }

    public void assertOwner() {
        bindOwner();
    }

    public TradingCoreState snapshotState() {
        assertOwner();
        return RuntimeStateMaterializer.materialize(runtimeState, identities);
    }

    public LaneTopology topology() {
        assertOwner();
        return topology;
    }

    public int accountLaneId(long userId) {
        assertOwner();
        return topology.accountLaneId(userId);
    }

    public AccountLaneView accountLane(long userId) {
        assertOwner();
        return runtimeState.accountLane(userId);
    }

    public long committedCoreSequence() {
        assertOwner();
        return committedCoreSequence;
    }

    void commitCoreSequence(long coreSequence) {
        assertOwner();
        if (coreSequence != Math.incrementExact(committedCoreSequence)) {
            throw new IllegalStateException("global core commit sequence gap");
        }
        committedCoreSequence = coreSequence;
    }

    public long readFence(long requestedSequence) {
        assertOwner();
        if (requestedSequence < 0 || requestedSequence > committedCoreSequence) {
            throw new IllegalArgumentException("query is outside committed visibility");
        }
        return committedCoreSequence;
    }

    TradingRuntimeState runtimeStateForConstruction() {
        if (owner != null) assertOwner();
        return runtimeState;
    }

    RuntimeIdentityRegistry identitiesForConstruction() {
        if (owner != null) assertOwner();
        return identities;
    }

    public DeterministicExchangeCoreAdapter matcher() {
        assertOwner();
        return matcher;
    }

    DeterministicExchangeCoreAdapter matcherForConstruction() {
        return matcher;
    }

    CompletableFuture<Void> matcherReady() {
        return matcherReady;
    }

    public PositionUserIndex positionUsers() {
        assertOwner();
        return positionUsers;
    }

    PositionUserIndex positionUsersForConstruction() {
        return positionUsers;
    }

    public OpenInterestIndex openInterest() {
        assertOwner();
        return openInterest;
    }

    OpenInterestIndex openInterestForConstruction() {
        return openInterest;
    }

    public TriggerOrderIndex triggers() {
        assertOwner();
        return triggers;
    }

    TriggerOrderIndex triggersForConstruction() {
        return triggers;
    }

    public AlgoOrderIndex algos() {
        assertOwner();
        return algos;
    }

    AlgoOrderIndex algosForConstruction() {
        return algos;
    }

    public LiquidationIndex liquidations() {
        assertOwner();
        return liquidations;
    }

    LiquidationIndex liquidationsForConstruction() {
        return liquidations;
    }

    public CancelAllAfterIndex timers() {
        assertOwner();
        return timers;
    }

    CancelAllAfterIndex timersForConstruction() {
        return timers;
    }

    public ActiveOrderIndex activeOrders() {
        assertOwner();
        return activeOrders;
    }

    ActiveOrderIndex activeOrdersForConstruction() {
        return activeOrders;
    }

    public AdlPositionIndex adlPositions() {
        assertOwner();
        return adlPositions;
    }

    AdlPositionIndex adlPositionsForConstruction() {
        return adlPositions;
    }

    public RiskSnapshotIndex riskSnapshots() {
        assertOwner();
        return riskSnapshots;
    }

    RiskSnapshotIndex riskSnapshotsForConstruction() {
        return riskSnapshots;
    }

    void commitRuntimeTransition(RuntimeCommitEntry entry,
                                 long beforeBusinessStateHash, long afterBusinessStateHash) {
        assertOwner();
        if (entry == null || entry.productLine() != productLine || entry.revision() < committedRevision
                || beforeBusinessStateHash != committedBusinessStateHash) {
            throw new IllegalStateException("typed runtime transition is out of order");
        }
        positionUsers.update(entry.users());
        openInterest.update(entry.users());
        triggers.update(entry.triggers());
        algos.update(entry.algoOrders());
        liquidations.update(entry.liquidations());
        timers.update(entry.timers());
        activeOrders.update(entry.orders());
        adlPositions.update(entry.users());
        riskSnapshots.update(entry.riskSnapshots());
        committedRevision = entry.revision();
        committedBusinessStateHash = afterBusinessStateHash;
        runtimeState.clearChangedKeys();
    }

    public void restoreStateOnly(TradingCoreState restored) {
        assertOwner();
        if (restored == null || restored.productLine() != productLine) {
            throw new IllegalArgumentException("invalid matcher state");
        }
        java.util.Map<Long, com.surprising.aeron.service.state.CoreFeePolicyState> feePolicies =
                runtimeState.feePoliciesSnapshot();
        java.util.Map<Long, com.surprising.aeron.service.state.TransferRuntime> pendingTransfers =
                runtimeState.pendingTransfersSnapshot();
        restoreIndexes(restored);
        runtimeState.restoreFeePolicies(feePolicies);
        runtimeState.restorePendingTransfers(pendingTransfers);
    }

    private void restoreIndexes(TradingCoreState restored) {
        runtimeState.close();
        identities = new RuntimeIdentityRegistry();
        runtimeState = RuntimeStateProjector.project(restored, identities, topology);
        runtimeState.bindOwner();
        runtimeState.startAccountLanes();
        runtimeState.clearChangedKeys();
        committedRevision = restored.revision();
        committedBusinessStateHash = restored.businessStateHash();
        positionUsers.rebuild(restored);
        openInterest.rebuild(restored);
        triggers.rebuild(restored);
        algos.rebuild(restored);
        liquidations.rebuild(restored);
        timers.rebuild(restored);
        activeOrders.rebuild(restored);
        adlPositions.rebuild(restored);
        riskSnapshots.rebuild(restored);
    }

    @Override
    public void close() {
        runtimeState.close();
        matcher.close();
    }
}
