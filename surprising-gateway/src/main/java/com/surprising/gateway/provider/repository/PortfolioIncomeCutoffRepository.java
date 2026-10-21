package com.surprising.gateway.provider.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.gateway.provider.model.PortfolioIncomeRows.*;
import java.math.BigInteger;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 必要关联只读例外：core_portfolio_cutoff认证整个用户集的提交前缀，LEFT JOIN同产品/截止时刻的
 * core_portfolio_equity读取此用户权益。完整前缀缺少用户行可证明零，缺少截止前缀本身不能证明零；
 * 由Service的同一REPEATABLE_READ事务读取，禁止拆成逐用户补读。 */
@Repository
public class PortfolioIncomeCutoffRepository {
    private final JdbcTemplate jdbc;

    public PortfolioIncomeCutoffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

    public List<Cut> cuts(long userId, long opening, long oldestCurrent, long newestCurrent) {
        return jdbc.query("""
                SELECT c.*,e.user_id AS account_user,e.complete,e.net_asset_units::text
                  FROM core_portfolio_cutoff c LEFT JOIN core_portfolio_equity e
                    ON e.product_line=c.product_line AND e.cutoff_epoch_ms=c.cutoff_epoch_ms AND e.user_id=?
                 WHERE (c.cutoff_epoch_ms=? OR c.cutoff_epoch_ms>=?) AND c.cutoff_epoch_ms<=?
                """, (rs, row) -> {
            boolean absent = rs.getObject("account_user") == null;
            // A cut certifies the entire user set. Absence in that complete
            // prefix proves zero; absence of the cut itself proves nothing.
            var units = absent ? Map.<String, BigInteger>of() : units(rs.getString("net_asset_units"));
            return new Cut(ProductLine.valueOf(rs.getString("product_line")), rs.getLong("cutoff_epoch_ms"),
                    rs.getLong("cluster_position"), rs.getLong("state_sequence"), rs.getLong("flow_covered_from_epoch_ms"),
                    rs.getBoolean("day_opening"), units != null && (absent || rs.getBoolean("complete")),
                    units == null ? Map.of() : units);
        }, userId, opening, oldestCurrent, newestCurrent);
    }

    private Map<String, BigInteger> units(String text) {
        try {
            Map<?, ?> raw = json.readValue(text, Map.class);
            var units = new TreeMap<String, BigInteger>();
            for (var entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String asset) || !asset.matches("[A-Z0-9]{2,20}")
                        || !(entry.getValue() instanceof String value) || !value.matches("-?[0-9]{1,40}")) return null;
                units.put(asset, new BigInteger(value));
            }
            return Map.copyOf(units);
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
