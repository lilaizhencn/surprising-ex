package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;
import java.util.Set;
import java.util.HashSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 必要关联读取例外：合并策略表market_maker_strategies及设置表market_maker_business_settings中的做市账号。包含停用策略以避免历史普通用户成交被重分类；本批一次读取，无写入。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class MarketMakerProjectionAccountsRepository {
    private final JdbcTemplate jdbc;

    public MarketMakerProjectionAccountsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Set<Long> marketMakerAccounts(ProductLine product) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT unnest(account_ids) FROM market_maker_strategies WHERE product_line=?
                UNION SELECT value::bigint FROM market_maker_business_settings,
                    jsonb_array_elements_text(settings->'trade'->'accountIds') WHERE product_line=?
                """, Long.class, product.name(), product.name()));
    }
}
