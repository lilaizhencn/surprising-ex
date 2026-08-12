package com.surprising.risk.provider.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.config.RiskProperties;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 风险账户快照仓储，只负责 {@code risk_account_snapshots} 表。 */
@Repository
public class RiskAccountSnapshotRepository {

    private static final String DEFAULT_ACCOUNT_TYPE = "USDT_PERPETUAL";

    private final JdbcTemplate jdbcTemplate;
    private final RiskProperties properties;

    public RiskAccountSnapshotRepository(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, new RiskProperties());
    }

    @Autowired
    public RiskAccountSnapshotRepository(JdbcTemplate jdbcTemplate, RiskProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new RiskProperties() : properties;
    }

    public void saveAll(List<RiskAccountSnapshotResponse> snapshots) {
        if (snapshots.isEmpty()) {
            return;
        }
        int[] rows = jdbcTemplate.batchUpdate("""
                INSERT INTO risk_account_snapshots (
                    product_line, snapshot_id, user_id, account_type, settle_asset, wallet_balance_units,
                    unrealized_pnl_units, equity_units, maintenance_margin_units,
                    margin_ratio_ppm, status, event_time, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement statement, int index) throws java.sql.SQLException {
                RiskAccountSnapshotResponse row = snapshots.get(index);
                statement.setString(1, productLineForAccountType(row.accountType()).name());
                statement.setLong(2, row.snapshotId());
                statement.setLong(3, row.userId());
                statement.setString(4, row.accountType());
                statement.setString(5, row.settleAsset());
                statement.setLong(6, row.walletBalanceUnits());
                statement.setLong(7, row.unrealizedPnlUnits());
                statement.setLong(8, row.equityUnits());
                statement.setLong(9, row.maintenanceMarginUnits());
                statement.setLong(10, row.marginRatioPpm());
                statement.setString(11, row.status().name());
                statement.setTimestamp(12, Timestamp.from(row.eventTime()));
            }

            @Override
            public int getBatchSize() {
                return snapshots.size();
            }
        });
        requireCompleteBatch(rows, snapshots.size());
    }

    public Optional<RiskAccountSnapshotResponse> latest(long userId, String accountType, String settleAsset) {
        String normalizedAccountType = normalizeAccountType(accountType);
        return jdbcTemplate.query("""
                SELECT *
                  FROM risk_account_snapshots
                 WHERE product_line = ? AND user_id = ? AND account_type = ? AND settle_asset = ?
                 ORDER BY event_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> new RiskAccountSnapshotResponse(
                rs.getLong("snapshot_id"),
                rs.getLong("user_id"),
                rs.getString("account_type"),
                rs.getString("settle_asset"),
                rs.getLong("wallet_balance_units"),
                rs.getLong("unrealized_pnl_units"),
                rs.getLong("equity_units"),
                rs.getLong("maintenance_margin_units"),
                rs.getLong("margin_ratio_ppm"),
                RiskStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("event_time").toInstant()),
                productLineForAccountType(normalizedAccountType).name(), userId, normalizedAccountType, settleAsset)
                .stream().findFirst();
    }

    private String normalizeAccountType(String accountType) {
        return accountType == null || accountType.isBlank()
                ? DEFAULT_ACCOUNT_TYPE
                : accountType.trim().toUpperCase();
    }

    private ProductLine productLineForAccountType(String accountType) {
        return ProductLine.fromAccountTypeCode(normalizeAccountType(accountType))
                .orElse(properties.getKafka().isProductTopicsEnabled()
                        ? properties.getKafka().getProductLine()
                        : ProductLine.LINEAR_PERPETUAL);
    }

    private void requireCompleteBatch(int[] rows, int expectedSize) {
        if (rows.length != expectedSize) {
            throw new IllegalStateException("写入风险账户快照批次不完整");
        }
        for (int row : rows) {
            if (row != 1 && row != Statement.SUCCESS_NO_INFO) {
                throw new IllegalStateException("写入风险账户快照失败");
            }
        }
    }
}
