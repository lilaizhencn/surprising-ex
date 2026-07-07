package com.surprising.trading.matching.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.matching.config.MatchingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MatchingProtectionRepositoryTest {

    @Test
    void leavesProtectionQueriesUnfilteredForLegacyTopics() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        MatchingProtectionRepository repository = new MatchingProtectionRepository(jdbcTemplate);

        repository.wouldSelfTrade(1001L, "BTC-USDT", 1L, OrderSide.BUY, 65_000L);

        assertThat(jdbcTemplate.sql).doesNotContain("product_line = ?");
        assertThat(jdbcTemplate.args).hasSize(8);
    }

    @Test
    void filtersSelfTradeProtectionByProductLineWhenProductTopicsAreEnabled() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        MatchingProtectionRepository repository =
                new MatchingProtectionRepository(jdbcTemplate, properties(ProductLine.OPTION));

        repository.wouldSelfTrade(1001L, "BTC-USDT-260626-C-65000", 1L, OrderSide.BUY, 65_000L);

        assertThat(jdbcTemplate.sql).contains("product_line = ?");
        assertThat(jdbcTemplate.args).hasSize(9);
        assertThat(jdbcTemplate.args[4]).isEqualTo("OPTION");
    }

    @Test
    void filtersVersionProtectionByProductLineWhenProductTopicsAreEnabled() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        MatchingProtectionRepository repository =
                new MatchingProtectionRepository(jdbcTemplate, properties(ProductLine.LINEAR_DELIVERY));

        repository.hasOpenOrdersWithDifferentInstrumentVersion("BTC-USDT-260626", 2L, 101L);

        assertThat(jdbcTemplate.sql).contains("product_line = ?");
        assertThat(jdbcTemplate.args).hasSize(4);
        assertThat(jdbcTemplate.args[3]).isEqualTo("LINEAR_DELIVERY");
    }

    private MatchingProperties properties(ProductLine productLine) {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(productLine);
        return properties;
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] args = new Object[0];

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            this.sql = sql;
            this.args = args == null ? new Object[0] : args;
            if (requiredType == Boolean.class) {
                return requiredType.cast(false);
            }
            throw new UnsupportedOperationException("unsupported required type " + requiredType);
        }
    }
}
