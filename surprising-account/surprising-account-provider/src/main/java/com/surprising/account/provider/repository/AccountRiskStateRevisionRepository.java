package com.surprising.account.provider.repository;

import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 为每个产品线用户分配单调递增的账户读模型修订号。 */
@Repository
public class AccountRiskStateRevisionRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountRiskStateRevisionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 读取用户当前账户修订号；不存在的用户视为零，供订单冻结版本栅栏使用。 */
    public long current(ProductLine productLine, long userId) {
        if (productLine == null || userId <= 0L) {
            throw new IllegalArgumentException("productLine and userId are required");
        }
        Long revision = jdbcTemplate.queryForObject("""
                SELECT COALESCE((SELECT revision
                                   FROM account_risk_state_revisions
                                  WHERE product_line = ?
                                    AND user_id = ?), 0)
                """, Long.class, productLine.name(), userId);
        return revision == null ? 0L : revision;
    }

    /**
     * 为异步快照投影抢占单调修订号。
     *
     * <p>成功时会在当前事务内锁住用户修订行；调用方随后替换全部状态表，任一步失败都会
     * 回滚修订号和投影数据。旧事件不会覆盖新状态，重复事件直接视为已完成。</p>
     */
    public boolean beginProjection(ProductLine productLine,
                                   long userId,
                                   long revision,
                                   Instant now) {
        if (productLine == null || userId <= 0L || revision <= 0L || now == null) {
            throw new IllegalArgumentException("projection revision arguments are required");
        }
        List<Long> rows = jdbcTemplate.query("""
                INSERT INTO account_risk_state_revisions (product_line, user_id, revision, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (product_line, user_id) DO UPDATE
                   SET revision = EXCLUDED.revision,
                       updated_at = EXCLUDED.updated_at
                 WHERE account_risk_state_revisions.revision < EXCLUDED.revision
             RETURNING revision
                """, (rs, rowNum) -> rs.getLong("revision"), productLine.name(), userId, revision,
                Timestamp.from(now));
        return rows.size() == 1;
    }
}
