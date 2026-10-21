package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;
import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.aeron.service.orchestration.replay.CommittedTradeReplay.CommittedBalanceFlow;
import java.math.BigDecimal;

/** 仅访问core_portfolio_cash_flow；保留精确金额、不可变命令事实和划转前缀对账记录。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class PortfolioCashFlowRepository {
    private final JdbcTemplate jdbc;

    public PortfolioCashFlowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void persist(Collection<CommittedBalanceFlow> flows) {
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
                """, flows, 256, (s, flow) -> {
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
    public void prune(ProductLine product, long before) {
        jdbc.update("DELETE FROM core_portfolio_cash_flow WHERE product_line=? AND transfer_id IS NULL AND occurred_at_epoch_ms<?", product.name(),before);
    }
}
