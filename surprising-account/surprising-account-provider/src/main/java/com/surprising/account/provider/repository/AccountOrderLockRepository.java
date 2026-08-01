package com.surprising.account.provider.repository;

import com.surprising.account.api.model.AccountType;
import com.surprising.product.api.ProductLine;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 trading_orders 表的隔离订单冻结汇总，不参与业务编排。 */
@Repository
public class AccountOrderLockRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountOrderLockRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Long> sumOpenIsolatedByAsset(ProductLine productLine,
                                                    long userId,
                                                    AccountType accountType) {
        return jdbcTemplate.query("""
                SELECT reservation_asset,
                       COALESCE(SUM(CASE WHEN quantity_steps = 0 THEN 0
                                         ELSE reserved_units * remaining_quantity_steps / quantity_steps END), 0)
                         AS locked_units
                  FROM trading_orders
                 WHERE product_line = ?
                   AND user_id = ?
                   AND reservation_account_type = ?
                   AND margin_mode = 'ISOLATED'
                   AND reservation_asset IS NOT NULL
                   AND status IN ('PENDING_RESERVE', 'ACCEPTED', 'PARTIALLY_FILLED', 'CANCEL_REQUESTED')
                   AND remaining_quantity_steps > 0
                 GROUP BY reservation_asset
                """, (rs, rowNum) -> Map.entry(rs.getString("reservation_asset"), rs.getLong("locked_units")),
                productLine.name(), userId, accountType.name())
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue, Math::addExact, LinkedHashMap::new));
    }
}
