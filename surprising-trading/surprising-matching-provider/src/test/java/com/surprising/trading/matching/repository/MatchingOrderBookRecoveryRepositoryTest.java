package com.surprising.trading.matching.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.matching.config.MatchingProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MatchingOrderBookRecoveryRepositoryTest {

    @Test
    void leavesRecoverableOrdersUnfilteredForLegacyTopics() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        MatchingOrderBookRecoveryRepository repository = new MatchingOrderBookRecoveryRepository(jdbcTemplate);

        repository.recoverableOpenOrdersAfter(Instant.EPOCH, 0L, 100);

        assertThat(jdbcTemplate.sql).doesNotContain("i.contract_type = ?");
        assertThat(jdbcTemplate.args).hasSize(4);
    }

    @Test
    void filtersRecoverableOrdersByProductLineWhenProductTopicsAreEnabled() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingOrderBookRecoveryRepository repository =
                new MatchingOrderBookRecoveryRepository(jdbcTemplate, properties);

        repository.recoverableOpenOrdersAfter(Instant.EPOCH, 0L, 100);

        assertThat(jdbcTemplate.sql).contains("i.contract_type = ?");
        assertThat(jdbcTemplate.args).hasSize(5);
        assertThat(jdbcTemplate.args[0]).isEqualTo("VANILLA_OPTION");
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] args = new Object[0];

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.args = args == null ? new Object[0] : args;
            return List.of();
        }
    }
}
