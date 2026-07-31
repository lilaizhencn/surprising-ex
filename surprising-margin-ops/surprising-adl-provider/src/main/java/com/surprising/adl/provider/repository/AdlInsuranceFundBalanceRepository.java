package com.surprising.adl.provider.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 只负责读取 {@code insurance_fund_balances} 表。
 */
@Repository
public class AdlInsuranceFundBalanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdlInsuranceFundBalanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Long> findBalances(String accountType, List<String> assets) {
        if (assets == null || assets.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(assets.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(accountType);
        args.addAll(assets);
        Map<String, Long> result = new HashMap<>();
        jdbcTemplate.query("""
                SELECT asset, balance_units
                  FROM insurance_fund_balances
                 WHERE account_type = ? AND asset IN (%s)
                """.formatted(placeholders), rs -> {
            while (rs.next()) {
                result.put(rs.getString("asset"), rs.getLong("balance_units"));
            }
            return null;
        }, args.toArray());
        return Map.copyOf(result);
    }
}
