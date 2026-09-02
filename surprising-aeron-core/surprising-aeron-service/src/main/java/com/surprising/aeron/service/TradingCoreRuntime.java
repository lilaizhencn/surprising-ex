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
import com.surprising.aeron.service.state.RuntimeFactFrame;
import com.surprising.aeron.service.state.RuntimeFactIndexes;
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
    private final RuntimeFactIndexes factIndexes;
    private RuntimeIdentityRegistry identities;
    private TradingRuntimeState runtimeState;
    private long committedRevision;
    private long committedBusinessStateHash;
    private long committedCoreSequence;
    private final CompletableFuture<Void> matcherReady;
    private Thread owner;
    private boolean activated;

    public TradingCoreRuntime(ProductLine productLine, TradingCoreState initialState) {
        this(productLine, initialState, 0, null);
    }

    public TradingCoreRuntime(
            ProductLine productLine,
            TradingCoreState initialState,
            long coreSequence,
            MatcherSnapshot matcherSnapshot) {
        this(productLine, initialState, coreSequence, matcherSnapshot, true);
    }

    private TradingCoreRuntime(
            ProductLine productLine,
            TradingCoreState initialState,
            long coreSequence,
            MatcherSnapshot matcherSnapshot,
            boolean activateImmediately) {
        this(productLine, initialState, coreSequence, matcherSnapshot, activateImmediately,
                initialState == null ? 0 : initialState.businessStateHash());
    }

    private TradingCoreRuntime(
            ProductLine productLine,
            TradingCoreState initialState,
            long coreSequence,
            MatcherSnapshot matcherSnapshot,
            boolean activateImmediately,
            long expectedCoreBusinessStateHash) {
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
        this.activeOrders = new ActiveOrderIndex(initialState, identities);
        this.matcher = matcherSnapshot == null
                ? new DeterministicExchangeCoreAdapter(activateImmediately)
                : new DeterministicExchangeCoreAdapter(
                        initialState, activeOrders.orders(), coreSequence, matcherSnapshot, activateImmediately,
                        expectedCoreBusinessStateHash);
        this.positionUsers = new PositionUserIndex(initialState, identities);
        this.openInterest = new OpenInterestIndex(initialState, identities);
        this.triggers = new TriggerOrderIndex(initialState);
        this.algos = new AlgoOrderIndex(initialState);
        this.liquidations = new LiquidationIndex(initialState);
        this.timers = new CancelAllAfterIndex(initialState);
        this.adlPositions = new AdlPositionIndex(initialState, identities);
        this.riskSnapshots = new RiskSnapshotIndex(initialState);
        this.factIndexes = new RuntimeFactIndexes(positionUsers, openInterest, triggers, algos, liquidations,
                timers, activeOrders, adlPositions, riskSnapshots);
        this.matcherReady = CompletableFuture.completedFuture(null);
        if (activateImmediately) activate();
    }

    static TradingCoreRuntime passive(ProductLine productLine, TradingCoreState initialState,
                                      long coreSequence, MatcherSnapshot matcherSnapshot) {
        return new TradingCoreRuntime(productLine, initialState, coreSequence, matcherSnapshot, false);
    }

    static TradingCoreRuntime passive(ProductLine productLine, TradingCoreState initialState,
                                      long coreSequence, MatcherSnapshot matcherSnapshot,
                                      long expectedCoreBusinessStateHash) {
        return new TradingCoreRuntime(productLine, initialState, coreSequence, matcherSnapshot, false,
                expectedCoreBusinessStateHash);
    }

    void activate() {
        if (activated) return;
        Thread current = Thread.currentThread();
        if (owner != null && owner != current) {
            throw new IllegalStateException("trading runtime is bound to another thread");
        }
        owner = current;
        runtimeState.bindOwner();
        identities.assertOwner();
        runtimeState.startAccountLanes();
        matcher.activate();
        activated = true;
    }

    void releaseOwnerForHandoff() {
        if (activated) throw new IllegalStateException("active trading runtime cannot change owner");
        runtimeState.releaseOwnerForHandoff();
        identities.releaseOwnerForHandoff();
        owner = null;
    }

    boolean activated() { return activated; }

    public void bindOwner() {
        Thread current = Thread.currentThread();
        if (owner == null) {
            activate();
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

    void commitRuntimeTransition(RuntimeFactFrame entry,
                                 long beforeBusinessStateHash, long afterBusinessStateHash) {
        assertOwner();
        if (entry == null || entry.productLine() != productLine || entry.revision() < committedRevision
                || beforeBusinessStateHash != committedBusinessStateHash) {
            throw new IllegalStateException("typed runtime transition is out of order");
        }
        factIndexes.apply(entry);
        committedRevision = entry.revision();
        committedBusinessStateHash = afterBusinessStateHash;
    }

    void commitRuntimeTransition(TradingRuntimeState.PreparedFactFrame entry,
                                 long beforeBusinessStateHash, long afterBusinessStateHash) {
        assertOwner();
        if (entry == null || entry.metadata().afterRevision() < committedRevision
                || beforeBusinessStateHash != committedBusinessStateHash) {
            throw new IllegalStateException("runtime fact transition is out of order: afterRevision="
                    + (entry == null ? "null" : entry.metadata().afterRevision())
                    + ", committedRevision=" + committedRevision
                    + ", beforeBusinessStateHash=" + beforeBusinessStateHash
                    + ", committedBusinessStateHash=" + committedBusinessStateHash);
        }
        factIndexes.apply(entry.builder(), entry.identities());
        committedRevision = entry.metadata().afterRevision();
        committedBusinessStateHash = afterBusinessStateHash;
    }

    void restoreCommittedConsumers(TradingCoreState state, long revision, long businessStateHash) {
        if (owner != null) assertOwner();
        factIndexes.rebuild(state, identities);
        committedRevision = revision;
        committedBusinessStateHash = businessStateHash;
    }

    void rebaseInitialBusinessStateHash(long expectedBefore, long after) {
        if (owner != null) assertOwner();
        if (committedCoreSequence != 0 || committedBusinessStateHash != expectedBefore) {
            throw new IllegalStateException("runtime business hash rebase is outside initial state");
        }
        committedBusinessStateHash = after;
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
        factIndexes.rebuild(restored, identities);
    }

    @Override
    public void close() {
        runtimeState.close();
        matcher.close();
    }

    void closeOwnerState() {
        runtimeState.close();
    }

    void closeMatcher() {
        matcher.close();
    }
}
