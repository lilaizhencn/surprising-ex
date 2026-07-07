package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class FeeTierRepositoryTest {

    @Test
    void metricsReadsProductBalancesForNonDefaultProductLine() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        FeeTierRepository repository = new FeeTierRepository(jdbcTemplate);
        Instant since = Instant.parse("2026-07-01T00:00:00Z");

        repository.metrics(ProductLine.INVERSE_DELIVERY, 1001L, since);

        assertThat(jdbcTemplate.queryForObjectSql).hasSize(2);
        assertThat(jdbcTemplate.queryForObjectSql.get(1))
                .contains("FROM account_product_balances b")
                .contains("b.account_type = ?");
        assertThat(jdbcTemplate.queryForObjectArgs.get(1))
                .containsExactly("COIN_DELIVERY", 1001L, "COIN_DELIVERY", "COIN_DELIVERY", 1001L);
    }

    @Test
    void candidateUsersReadsProductBalancesForNonDefaultProductLine() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        FeeTierRepository repository = new FeeTierRepository(jdbcTemplate);
        Instant since = Instant.parse("2026-07-01T00:00:00Z");

        repository.candidateUsers(ProductLine.INVERSE_DELIVERY, since, 100);

        assertThat(jdbcTemplate.querySql)
                .contains("FROM account_product_balances")
                .contains("account_type = ?");
        assertThat(jdbcTemplate.queryArgs)
                .containsExactly(Timestamp.from(since), "INVERSE_DELIVERY",
                        Timestamp.from(since), "INVERSE_DELIVERY",
                        "COIN_DELIVERY", "COIN_DELIVERY", "COIN_DELIVERY",
                        "INVERSE_DELIVERY", 100);
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> queryForObjectSql = new ArrayList<>();
        private final List<Object[]> queryForObjectArgs = new ArrayList<>();
        private String querySql;
        private Object[] queryArgs = new Object[0];

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            queryForObjectSql.add(sql);
            queryForObjectArgs.add(args == null ? new Object[0] : args);
            if (requiredType == Number.class) {
                return (T) Long.valueOf(0L);
            }
            return null;
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            querySql = sql;
            queryArgs = args == null ? new Object[0] : args;
            return List.of();
        }
    }
}
