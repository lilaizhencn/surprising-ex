package com.surprising.realtime.provider.export;

import com.surprising.aeron.service.orchestration.CommittedPortfolioSnapshot;
import com.surprising.aeron.service.orchestration.CommittedTradeReplay.CommittedBalanceFlow;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Export-thread financial read model; authoritative balances remain solely in the Core. */
final class CommittedPortfolioHistory {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json = new ObjectMapper();

    CommittedPortfolioHistory(JdbcTemplate jdbc, TransactionTemplate transaction) {
        this.jdbc = jdbc;
        this.transaction = transaction;
    }

    Start start(ProductLine product, long checkpointPosition, long checkpointTimestamp) {
        jdbc.update("""
                INSERT INTO core_portfolio_projection_start(product_line,cluster_position,occurred_at_epoch_ms) VALUES (?,?,?)
                ON CONFLICT (product_line) DO NOTHING
                """, product.name(), checkpointPosition, checkpointTimestamp);
        return jdbc.queryForObject("""
                SELECT cluster_position,occurred_at_epoch_ms FROM core_portfolio_projection_start WHERE product_line=?
                """, (rs, row) -> new Start(rs.getLong(1), rs.getLong(2)), product.name());
    }

    boolean initialized(ProductLine product) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM core_portfolio_projection_start
                  WHERE product_line=? AND cluster_position=0 AND occurred_at_epoch_ms=0 AND ready)
                """, Boolean.class, product.name()));
    }

    void certifyGenesis(ProductLine product) {
        jdbc.update("""
                INSERT INTO core_portfolio_projection_start VALUES (?,0,0,true)
                ON CONFLICT (product_line) DO UPDATE SET cluster_position=0,occurred_at_epoch_ms=0,ready=true
                """, product.name());
    }

    /** One atomic export batch; SQL rejects a conflict even when JDBC rewrites batch inserts. */
    void persist(List<CommittedBalanceFlow> flows) {
        if (flows.isEmpty()) return;
        transaction.executeWithoutResult(tx -> persistFacts(flows));
    }

    private void persistFacts(List<CommittedBalanceFlow> flows) {
        // PostgreSQL's rewritten multi-row UPSERT cannot affect one key twice.
        // This bounded batch index holds references only, never an account state copy.
        var unique = new LinkedHashMap<FlowIdentity, CommittedBalanceFlow>();
        for (var flow : flows) {
            var previous = unique.putIfAbsent(new FlowIdentity(flow.product(), flow.commandId(), flow.userId()), flow);
            if (previous != null && !previous.equals(flow))
                throw new IllegalStateException("cash-flow batch contains conflicting command facts");
        }
        jdbc.batchUpdate("""
                INSERT INTO core_portfolio_cash_flow AS old
                  (product_line,command_id,user_id,cluster_position,occurred_at_epoch_ms,asset,amount_units,
                   transfer_id,source_product_line,target_product_line,source_user_id,target_user_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT (product_line,command_id,user_id) DO UPDATE SET amount_units=CASE
                WHEN (old.cluster_position,old.occurred_at_epoch_ms,old.asset,old.amount_units,old.transfer_id,
                       old.source_product_line,old.target_product_line,old.source_user_id,old.target_user_id)
                  IS NOT DISTINCT FROM
                      (EXCLUDED.cluster_position,EXCLUDED.occurred_at_epoch_ms,EXCLUDED.asset,EXCLUDED.amount_units,
                       EXCLUDED.transfer_id,EXCLUDED.source_product_line,EXCLUDED.target_product_line,
                       EXCLUDED.source_user_id,EXCLUDED.target_user_id)
                THEN old.amount_units ELSE NULL END
                """, unique.values(), 256, (s, flow) -> {
            var transfer = flow.transfer();
            s.setString(1, flow.product().name()); s.setObject(2, flow.commandId()); s.setLong(3, flow.userId());
            s.setLong(4, flow.clusterPosition()); s.setLong(5, flow.occurredAt()); s.setString(6, flow.asset());
            s.setBigDecimal(7, new BigDecimal(new java.math.BigInteger(flow.amountUnits())));
            s.setObject(8, transfer == null ? null : transfer.transferId());
            s.setString(9, transfer == null ? null : transfer.sourceProductLine().name());
            s.setString(10, transfer == null ? null : transfer.targetProductLine().name());
            s.setObject(11, transfer == null ? null : transfer.sourceUserId());
            s.setObject(12, transfer == null ? null : transfer.targetUserId());
        });
    }

    boolean contains(ProductLine product, long cutoff) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM core_portfolio_cutoff WHERE product_line=? AND cutoff_epoch_ms=?)
                """, Boolean.class, product.name(), cutoff));
    }

    /** Call only after earlier cash-flow batches commit. Metadata and all user rows publish atomically. */
    void publish(CommittedPortfolioSnapshot snapshot, long position, long stateSequence, long flowCoveredFrom, boolean opening) {
        transaction.executeWithoutResult(tx -> {
            int inserted = jdbc.update("""
                    INSERT INTO core_portfolio_cutoff VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT (product_line,cutoff_epoch_ms) DO NOTHING
                    """, snapshot.productLine().name(), snapshot.asOfEpochMillis(), position, stateSequence,
                    snapshot.sourceTimestamp(), flowCoveredFrom, opening);
            if (inserted == 0) {
                if (opening) jdbc.update("UPDATE core_portfolio_cutoff SET day_opening=true WHERE product_line=? AND cutoff_epoch_ms=?",
                        snapshot.productLine().name(), snapshot.asOfEpochMillis());
                return;
            }
            jdbc.batchUpdate("""
                    INSERT INTO core_portfolio_equity VALUES (?,?,?,?,?::jsonb)
                    """, snapshot.users().entrySet(), 256, (s, entry) -> {
                s.setString(1, snapshot.productLine().name()); s.setLong(2, snapshot.asOfEpochMillis());
                s.setLong(3, entry.getKey()); s.setBoolean(4, entry.getValue().complete());
                s.setString(5, json.writeValueAsString(entry.getValue().netAssetUnits()));
            });
        });
    }

    /** Retain daily opening and a bounded minute overlap. Transfer receipts remain for prefix reconciliation. */
    void prune(ProductLine product, long opening, long minute) {
        transaction.executeWithoutResult(tx -> {
            jdbc.update("""
                    DELETE FROM core_portfolio_cutoff WHERE product_line=?
                      AND ((NOT day_opening AND cutoff_epoch_ms<?) OR cutoff_epoch_ms<?)
                    """, product.name(), minute - 120_000, opening - 86_400_000);
            jdbc.update("DELETE FROM core_portfolio_cash_flow WHERE product_line=? AND transfer_id IS NULL AND occurred_at_epoch_ms<?",
                    product.name(), opening - 86_400_000);
        });
    }

    record Start(long position, long timestamp) {}
    private record FlowIdentity(ProductLine product, UUID commandId, long userId) {}
}
