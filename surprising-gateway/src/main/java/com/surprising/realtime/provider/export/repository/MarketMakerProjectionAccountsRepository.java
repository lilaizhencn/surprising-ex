package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 必要关联读取例外：合并策略表market_maker_strategies及设置表market_maker_business_settings中的做市账号。包含停用策略以避免历史普通用户成交被重分类；本批一次读取，无写入。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class MarketMakerProjectionAccountsRepository {
    private final JdbcTemplate jdbc;
    private final Set<Long> configuredMakerAccounts;

    public MarketMakerProjectionAccountsRepository(JdbcTemplate jdbc) {
        this(jdbc, "");
    }

    @Autowired
    public MarketMakerProjectionAccountsRepository(
            JdbcTemplate jdbc,
            @Value("${surprising.trade-export.internal-maker-accounts:}") String configuredAccounts) {
        this.jdbc = jdbc;
        if (configuredAccounts == null || configuredAccounts.isBlank()) {
            this.configuredMakerAccounts = Set.of();
        } else {
            Set<Long> parsed = new HashSet<>();
            for (String token : configuredAccounts.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) parsed.add(Long.parseLong(trimmed));
            }
            this.configuredMakerAccounts = Set.copyOf(parsed);
        }
    }

    public Set<Long> marketMakerAccounts(ProductLine product) {
        Set<Long> accounts = new HashSet<>(jdbc.queryForList("""
                SELECT unnest(account_ids) FROM market_maker_strategies WHERE product_line=?
                UNION SELECT value::bigint FROM market_maker_business_settings,
                    jsonb_array_elements_text(settings->'trade'->'accountIds') WHERE product_line=?
                """, Long.class, product.name(), product.name()));
        accounts.addAll(configuredMakerAccounts);
        return accounts;
    }
}
