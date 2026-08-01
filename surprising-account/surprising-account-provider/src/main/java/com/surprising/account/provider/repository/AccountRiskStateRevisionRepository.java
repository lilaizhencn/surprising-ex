package com.surprising.account.provider.repository;

import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 为每个产品线用户分配单调递增的账户读模型修订号。 */
@Repository
public class AccountRiskStateRevisionRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountRiskStateRevisionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 在账户命令事务中推进用户修订号。主键行锁把多个节点的同一用户更新串成一条顺序，
     * 事件发布失败时事务回滚，修订号也不会跳过一次已提交状态。
     */
    public long next(ProductLine productLine, long userId, Instant now) {
        if (productLine == null || userId <= 0 || now == null) {
            throw new IllegalArgumentException("productLine, userId and now are required");
        }
        Long revision = jdbcTemplate.queryForObject("""
                INSERT INTO account_risk_state_revisions (product_line, user_id, revision, updated_at)
                VALUES (?, ?, 1, ?)
                ON CONFLICT (product_line, user_id) DO UPDATE
                   SET revision = account_risk_state_revisions.revision + 1,
                       updated_at = EXCLUDED.updated_at
                RETURNING revision
                """, Long.class, productLine.name(), userId, Timestamp.from(now));
        if (revision == null || revision <= 0) {
            throw new IllegalStateException("account risk state revision was not allocated");
        }
        return revision;
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
}
