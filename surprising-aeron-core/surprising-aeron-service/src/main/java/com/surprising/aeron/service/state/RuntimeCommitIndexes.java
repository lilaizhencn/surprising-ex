package com.surprising.aeron.service.state;

public final class RuntimeCommitIndexes {
    private final PositionUserIndex positionUsers;
    private final OpenInterestIndex openInterest;
    private final TriggerOrderIndex triggers;
    private final AlgoOrderIndex algos;
    private final LiquidationIndex liquidations;
    private final CancelAllAfterIndex timers;
    private final ActiveOrderIndex activeOrders;
    private final AdlPositionIndex adlPositions;
    private final RiskSnapshotIndex riskSnapshots;
    private ApplyStats lastApplyStats = ApplyStats.EMPTY;

    public RuntimeCommitIndexes(PositionUserIndex positionUsers, OpenInterestIndex openInterest,
                                TriggerOrderIndex triggers, AlgoOrderIndex algos,
                                LiquidationIndex liquidations, CancelAllAfterIndex timers,
                                ActiveOrderIndex activeOrders, AdlPositionIndex adlPositions,
                                RiskSnapshotIndex riskSnapshots) {
        this.positionUsers = require(positionUsers, "position-user");
        this.openInterest = require(openInterest, "open-interest");
        this.triggers = require(triggers, "trigger");
        this.algos = require(algos, "algo");
        this.liquidations = require(liquidations, "liquidation");
        this.timers = require(timers, "timer");
        this.activeOrders = require(activeOrders, "active-order");
        this.adlPositions = require(adlPositions, "ADL-position");
        this.riskSnapshots = require(riskSnapshots, "risk-snapshot");
    }

    public void apply(RuntimeCommitPatch patch) {
        if (patch == null) throw new IllegalArgumentException("runtime commit patch is required");
        RuntimeCommitPatch.IdentityView identities = patch.identities();
        int positionUserVisits = 0;
        int openInterestVisits = 0;
        int triggerVisits = 0;
        int algoVisits = 0;
        int liquidationVisits = 0;
        int timerVisits = 0;
        int activeOrderVisits = 0;
        int adlPositionVisits = 0;
        int riskSnapshotVisits = 0;
        for (RuntimeCommitPatch.AccountLaneOwnerGroup group : patch.accountLaneGroups()) {
            if (!group.positions().isEmpty()) {
                positionUsers.apply(group.positions(), identities);
                positionUserVisits++;
                openInterest.apply(group.positions(), identities);
                openInterestVisits++;
            }
            if (!group.triggerOrders().isEmpty()) {
                triggers.apply(group.triggerOrders());
                triggerVisits++;
            }
            if (!group.algoOrders().isEmpty()) {
                algos.apply(group.algoOrders());
                algoVisits++;
            }
            if (!group.liquidations().isEmpty()) {
                liquidations.apply(group.liquidations(), identities);
                liquidationVisits++;
            }
            if (!group.timers().isEmpty()) {
                timers.apply(group.timers());
                timerVisits++;
            }
            if (!group.orders().isEmpty()) {
                activeOrders.apply(group.orders(), identities);
                activeOrderVisits++;
            }
            if (!group.positions().isEmpty()) {
                adlPositions.apply(group.positions(), identities);
                adlPositionVisits++;
            }
            if (!group.riskSnapshots().isEmpty()) {
                riskSnapshots.apply(group.riskSnapshots(), identities);
                riskSnapshotVisits++;
            }
        }
        lastApplyStats = new ApplyStats(positionUserVisits, openInterestVisits, triggerVisits, algoVisits,
                liquidationVisits, timerVisits, activeOrderVisits, adlPositionVisits, riskSnapshotVisits);
    }

    public void rebuild(TradingCoreState state, RuntimeIdentityRegistry identities) {
        positionUsers.rebuild(state, identities);
        openInterest.rebuild(state, identities);
        triggers.rebuild(state);
        algos.rebuild(state);
        liquidations.rebuild(state);
        timers.rebuild(state);
        activeOrders.rebuild(state, identities);
        adlPositions.rebuild(state, identities);
        riskSnapshots.rebuild(state);
        lastApplyStats = ApplyStats.EMPTY;
    }

    ApplyStats lastApplyStats() {
        return lastApplyStats;
    }

    private static <T> T require(T index, String name) {
        if (index == null) throw new IllegalArgumentException(name + " index is required");
        return index;
    }

    record ApplyStats(int positionUserVisits, int openInterestVisits, int triggerVisits, int algoVisits,
                      int liquidationVisits, int timerVisits, int activeOrderVisits,
                      int adlPositionVisits, int riskSnapshotVisits) {
        private static final ApplyStats EMPTY = new ApplyStats(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
