package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.realtime.provider.export.model.CommittedProjectionRows.DeliveryLedgerEntry;

/** 仅访问account_product_ledger_entries；提交交割/行权现金事实的幂等落账与冲突校验。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedDeliveryLedgerRepository {
    private final JdbcTemplate jdbc;

    public CommittedDeliveryLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertOrVerify(DeliveryLedgerEntry row) {
        int inserted = jdbc.update("""
                INSERT INTO account_product_ledger_entries
                  (entry_id,user_id,account_type,asset,amount_units,balance_after_units,
                   reference_type,reference_id,instrument_id,reason,created_at)
                VALUES(nextval('account_product_ledger_entry_seq'),?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(reference_type,reference_id,user_id,account_type,asset) DO NOTHING
                """, row.userId(), row.account(), row.asset(), row.amountUnits(), row.balanceAfterUnits(),
                row.referenceType(), row.reference(), row.instrumentId(), row.reason(), row.occurred());
        if (inserted == 0) {
            Integer matches = jdbc.queryForObject("""
                    SELECT count(*) FROM account_product_ledger_entries
                    WHERE reference_type=? AND reference_id=? AND user_id=?
                      AND account_type=? AND asset=? AND amount_units=? AND balance_after_units=?
                      AND instrument_id=? AND reason=? AND created_at=?
                    """, Integer.class, row.referenceType(), row.reference(), row.userId(), row.account(), row.asset(), row.amountUnits(),
                    row.balanceAfterUnits(), row.instrumentId(), row.reason(), row.occurred());
            if (matches == null || matches != 1) throw new IllegalStateException("conflicting committed delivery cash flow");
        }
    }
}
