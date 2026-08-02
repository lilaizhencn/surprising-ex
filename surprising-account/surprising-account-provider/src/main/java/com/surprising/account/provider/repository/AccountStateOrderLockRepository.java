package com.surprising.account.provider.repository;

import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 账户状态快照中的订单锁定汇总投影。
 *
 * <p>订单锁定属于用户分区状态的一部分，不能在恢复时重新扫描或 JOIN 交易订单表计算。
 * 本仓储只负责这一张投影表，事实裁决仍由本地 WAL/RocksDB 完成。</p>
 */
@Repository
public class AccountStateOrderLockRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountStateOrderLockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 用完整锁定列表替换单个用户的投影。 */
    public void replaceProjection(ProductLine productLine,
                                  long userId,
                                  List<LockProjectionRow> locks,
                                  Instant projectedAt) {
        jdbcTemplate.update("""
                DELETE FROM account_state_order_locks
                 WHERE product_line = ?
                   AND user_id = ?
                """, productLine.name(), userId);
        if (locks == null) {
            return;
        }
        for (LockProjectionRow lock : locks) {
            if (lock.lockedUnits() <= 0L) {
                continue;
            }
            jdbcTemplate.update("""
                    INSERT INTO account_state_order_locks (
                        product_line, user_id, asset, locked_units, updated_at
                    ) VALUES (?, ?, ?, ?, ?)
                    """, productLine.name(), userId, lock.asset(), lock.lockedUnits(),
                    Timestamp.from(projectedAt));
        }
    }

    public List<LockProjectionRow> findByUser(ProductLine productLine, long userId) {
        return jdbcTemplate.query("""
                SELECT asset, locked_units, updated_at
                  FROM account_state_order_locks
                 WHERE product_line = ?
                   AND user_id = ?
                 ORDER BY asset ASC
                """, (rs, rowNum) -> new LockProjectionRow(
                rs.getString("asset"), rs.getLong("locked_units"),
                rs.getTimestamp("updated_at").toInstant()), productLine.name(), userId);
    }

    public record LockProjectionRow(String asset, long lockedUnits, Instant updatedAt) {
    }
}
