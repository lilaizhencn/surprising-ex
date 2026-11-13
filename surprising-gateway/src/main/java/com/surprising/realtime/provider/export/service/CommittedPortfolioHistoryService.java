package com.surprising.realtime.provider.export.service;

import com.surprising.aeron.service.replay.CommittedPortfolioSnapshot;
import com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedBalanceFlow;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.provider.export.model.PortfolioProjectionStart;
import com.surprising.realtime.provider.export.repository.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/** Export-thread financial read model; authoritative balances remain solely in the Core. */
@org.springframework.stereotype.Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedPortfolioHistoryService {
    private final TransactionTemplate transaction;
    private final PortfolioStartRepository startRows;
    private final PortfolioCutoffRepository cutoffs;
    private final PortfolioCashFlowRepository cashFlows;
    private final PortfolioEquityRepository equities;
    private final CommittedBalanceAdjustmentRepository balanceAdjustments;

    public CommittedPortfolioHistoryService(PortfolioStartRepository startRows, PortfolioCutoffRepository cutoffs,
            PortfolioCashFlowRepository cashFlows, PortfolioEquityRepository equities,
            CommittedBalanceAdjustmentRepository balanceAdjustments,
            @org.springframework.beans.factory.annotation.Qualifier("committedProjectionTransactions") TransactionTemplate transaction) {
        this.startRows=startRows; this.cutoffs=cutoffs; this.cashFlows=cashFlows; this.equities=equities; this.transaction=transaction;
        this.balanceAdjustments = balanceAdjustments;
    }

    public PortfolioProjectionStart start(ProductLine product, long checkpointPosition, long checkpointTimestamp) {
        return startRows.start(product, checkpointPosition, checkpointTimestamp);
    }
    public boolean initialized(ProductLine product) { return startRows.initialized(product); }
    public void certifyGenesis(ProductLine product) { startRows.certifyGenesis(product); }

    /** One atomic export batch; SQL rejects a conflict even when JDBC rewrites batch inserts. */
    public void persist(List<CommittedBalanceFlow> flows) {
        if (flows.isEmpty()) return;
        transaction.executeWithoutResult(tx -> persistFacts(flows));
    }

    private void persistFacts(List<CommittedBalanceFlow> flows) {
        balanceAdjustments.persist(flows);
        // PostgreSQL's rewritten multi-row UPSERT cannot affect one key twice.
        // This bounded batch index holds references only, never an account state copy.
        var unique = new LinkedHashMap<FlowIdentity, CommittedBalanceFlow>();
        for (var flow : flows) {
            if ("0".equals(flow.amountUnits())) continue;
            var previous = unique.putIfAbsent(new FlowIdentity(flow.product(), flow.commandId(), flow.userId()), flow);
            if (previous != null && !previous.equals(flow))
                throw new IllegalStateException("cash-flow batch contains conflicting command facts");
        }
        if (!unique.isEmpty()) cashFlows.persist(unique.values());
    }

    public boolean contains(ProductLine product, long cutoff) { return cutoffs.contains(product, cutoff); }

    /** Call only after earlier cash-flow batches commit. Metadata and all user rows publish atomically. */
    public void publish(CommittedPortfolioSnapshot snapshot, long position, long stateSequence, long flowCoveredFrom, boolean opening) {
        transaction.executeWithoutResult(tx -> {
            int inserted = cutoffs.insert(snapshot, position, stateSequence, flowCoveredFrom, opening);
            if (inserted == 0) {
                if (opening) cutoffs.markOpening(snapshot.productLine(), snapshot.asOfEpochMillis());
                return;
            }
            equities.persist(snapshot);
        });
    }

    /** Retain daily opening and a bounded minute overlap. Transfer receipts remain for prefix reconciliation. */
    public void prune(ProductLine product, long opening, long minute) {
        transaction.executeWithoutResult(tx -> {
            cutoffs.prune(product, opening, minute);
            cashFlows.prune(product, opening - 86_400_000);
        });
    }

    private record FlowIdentity(ProductLine product, UUID commandId, long userId) {}
}
