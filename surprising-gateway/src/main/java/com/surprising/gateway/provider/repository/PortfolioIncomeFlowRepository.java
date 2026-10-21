package com.surprising.gateway.provider.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.gateway.provider.model.PortfolioIncomeRows.*;
import java.math.BigInteger;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅访问core_portfolio_cash_flow；保留用户范围、期初之后的流水及本人跨产品划转的前缀对账记录。 */
@Repository
public class PortfolioIncomeFlowRepository {
    private final JdbcTemplate jdbc;

    public PortfolioIncomeFlowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Flow> flows(long userId, long opening) {
        return jdbc.query("""
                SELECT * FROM core_portfolio_cash_flow WHERE user_id=?
                  AND (occurred_at_epoch_ms>=? OR (transfer_id IS NOT NULL AND source_user_id=? AND target_user_id=?))
                ORDER BY product_line,cluster_position
                """, (rs, row) -> {
            Long transfer = (Long) rs.getObject("transfer_id");
            return new Flow(ProductLine.valueOf(rs.getString("product_line")), rs.getLong("cluster_position"),
                    rs.getLong("occurred_at_epoch_ms"), rs.getString("asset"), rs.getBigDecimal("amount_units").toBigIntegerExact(),
                    transfer, transfer == null ? null : ProductLine.valueOf(rs.getString("source_product_line")),
                    transfer == null ? null : ProductLine.valueOf(rs.getString("target_product_line")),
                    rs.getLong("source_user_id"), rs.getLong("target_user_id"), rs.getObject("command_id").toString());
        }, userId, opening, userId, userId);
    }
}
