package com.surprising.realtime.provider.export.repository;

import com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedBalanceFlow;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只补齐 account_balance_adjustments；调用方与订单/回执/水位使用同一投影事务。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedBalanceAdjustmentRepository {
    private final JdbcTemplate jdbc;

    public CommittedBalanceAdjustmentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void persist(List<CommittedBalanceFlow> flows) {
        for (var flow : flows) {
            if (flow.product() == null)
                throw new IllegalArgumentException("cross-product balance adjustment");
            if (flow.transfer() != null) continue;
            if (flow.commandId() == null || flow.userId() <= 0 || flow.asset() == null
                    || flow.occurredAt() <= 0 || flow.clusterPosition() <= 0)
                throw new IllegalArgumentException("invalid committed balance adjustment");
            long amount = new BigInteger(flow.amountUnits()).longValueExact();
            var after = new BigInteger(flow.balanceAfterUnits());
            if (after.signum() < 0) throw new IllegalArgumentException("negative committed balance");
            var occurred = Instant.ofEpochMilli(flow.occurredAt());
            // 普通请求只保留首次来源，直接 Core 操作没有请求行；两者都不补造管理员历史。
            jdbc.query("""
                    UPDATE account_balance_adjustments
                       SET balance_after_units = COALESCE(balance_after_units, ?),
                           created_at = COALESCE(created_at, ?)
                     WHERE product_line = ? AND command_id = ? AND admin_user_id IS NOT NULL
                    RETURNING user_id, asset, amount_units, balance_after_units, created_at
                    """, row -> {
                if (row.getLong("user_id") != flow.userId() || !row.getString("asset").equals(flow.asset())
                        || row.getLong("amount_units") != amount
                        || !row.getBigDecimal("balance_after_units").toBigIntegerExact().equals(after)
                        || !row.getTimestamp("created_at").toInstant().equals(occurred))
                    throw new IllegalStateException("conflicting committed balance adjustment");
            }, new BigDecimal(after), Timestamp.from(occurred), flow.product().name(), flow.commandId());
        }
    }
}
