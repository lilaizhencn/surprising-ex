package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.product.api.ProductLine;
import java.util.Set;

public final class RuntimeCommitEntry {
    private final long sequence;
    private final RuntimeMutationDelta mutation;
    private final RuntimeIdentityRegistry identities;
    private final long previousRevision;
    private final RuntimeFundsDelta fundsDelta;
    private final RuntimeProjectionPoint projectionPoint;

    public RuntimeCommitEntry(long sequence, RuntimeMutationDelta mutation,
                              RuntimeIdentityRegistry identities, long previousRevision,
                              RuntimeFundsDelta fundsDelta) {
        if (sequence <= 0 || mutation == null || identities == null || previousRevision < 0
                || fundsDelta == null) {
            throw new IllegalArgumentException("invalid runtime commit entry");
        }
        this.sequence = sequence;
        this.mutation = mutation;
        this.identities = identities;
        this.previousRevision = previousRevision;
        this.fundsDelta = fundsDelta;
        this.projectionPoint = new RuntimeProjectionPoint(sequence, null);
    }

    public long sequence() { return sequence; }
    public RuntimeMutationDelta mutation() { return mutation; }
    public RuntimeIdentityRegistry identities() { return identities; }
    public RuntimeFundsDelta fundsDelta() { return fundsDelta; }
    public RuntimeProjectionPoint projectionPoint() { return projectionPoint; }
    public Set<Long> changedUserIds() { return mutation.users().changedKeys(); }
    public Set<Integer> changedTreasuryAssetIds() { return mutation.treasury().assets().changedKeys(); }
    public ProductLine productLine() { return mutation.productLine(); }
    public long revision() { return Math.subtractExact(mutation.revision(), mutation.pendingReservationCount()); }

    public TradingCoreState project(TradingCoreState state) {
        return RuntimeStateMaterializer.materializeTransition(mutation, identities, state);
    }

    void completeProjection(TradingCoreState state) {
        projectionPoint.complete(state);
    }

    public long afterNextLiquidationId() { return mutation.nextLiquidationId(); }
    public CoreRiskScanControlView afterRiskScanControl() { return mutation.riskScanControl(); }

    public boolean changesBusinessState() {
        return revision() != previousRevision;
    }

}
