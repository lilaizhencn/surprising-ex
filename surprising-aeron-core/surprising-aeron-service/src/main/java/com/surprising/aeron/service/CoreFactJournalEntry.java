package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreExportEvent;
import com.surprising.aeron.protocol.CoreMatcherTransition;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.state.FundsDelta;
import com.surprising.aeron.service.state.RuntimeFundsDelta;
import com.surprising.aeron.service.state.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.RuntimeProjectionJournal;
import com.surprising.aeron.service.state.RuntimeProjectionPoint;
import com.surprising.aeron.service.state.TradingCoreState;
import java.util.List;

record CoreFactJournalEntry(
        CoreMessage command,
        ResponseStatus status,
        CoreResultCode resultCode,
        long appliedCommandCount,
        long businessStateHash,
        long beforeBusinessStateHash,
        long beforeFundsStateHash,
        long fundsStateHash,
        long topologyHash,
        long laneRevisionHash,
        CoreMatcherTransition matcherTransition,
        long clusterPosition,
        RuntimeProjectionJournal projections,
        RuntimeProjectionPoint beforeProjection,
        RuntimeProjectionPoint afterProjection,
        CoreCommandDelta delta,
        List<String> treasuryAssets,
        RuntimeFundsDelta primitiveFunds,
        RuntimeIdentityRegistry identities,
        boolean externalAdjustment,
        TerminalStateRetention terminalRetention) implements CoreExportState.FactRecord {

    CoreFactJournalEntry {
        if (command == null || status == null || resultCode == null || appliedCommandCount < 0
                || matcherTransition == null || clusterPosition < 0 || projections == null
                || beforeProjection == null || afterProjection == null || delta == null
                || treasuryAssets == null || primitiveFunds == null
                || identities == null || terminalRetention == null) {
            throw new IllegalArgumentException("invalid typed Core Fact journal entry");
        }
        treasuryAssets = List.copyOf(treasuryAssets);
    }

    int itemCount() {
        return delta.userIds().size() + delta.orderIds().size() + delta.liquidationIds().size()
                + delta.triggerOrderIds().size() + treasuryAssets.size() + delta.executions().size()
                + delta.fundingPayments().size() + primitiveFunds.postingCount()
                + (externalAdjustment ? delta.userIds().size() + treasuryAssets.size() : 0);
    }

    @Override
    public CoreExportEvent materialize(long sequence) {
        TradingCoreState before = projections.await(beforeProjection);
        TradingCoreState after = projections.await(afterProjection);
        CoreCommandDelta fact = CoreProbeState.materializeFactDelta(
                before, after, delta, treasuryAssets);
        FundsDelta funds = primitiveFunds.materialize(identities, externalAdjustment);
        CoreExportEvent event = new CoreExportEvent(sequence, appliedCommandCount, businessStateHash,
                command.header().commandId(), command.header().messageType(), status, resultCode,
                command.header().userId(), command.payloadUnsafe(), fact.changedUsers(), fact.changedOrders(),
                fact.executions(), fact.fundingPayments(), fact.changedLiquidations(),
                fact.changedTreasuryAssets(), fact.changedTriggerOrders(), beforeBusinessStateHash,
                beforeFundsStateHash, fundsStateHash, matcherTransition.routeVersion(), topologyHash,
                laneRevisionHash, appliedCommandCount, matcherTransition, clusterPosition, funds.views());
        if (beforeProjection != afterProjection) {
            terminalRetention.observe(after, sequence, delta.orderIds(), delta.liquidationIds(),
                    delta.triggerOrderIds());
        }
        return event;
    }
}
