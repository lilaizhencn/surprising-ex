package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class InstrumentRuleRepositoryTest {

    @Test
    void leavesRuleLookupUnfilteredForLegacyTopics() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        InstrumentRuleRepository repository = new InstrumentRuleRepository(jdbcTemplate);

        repository.currentRule("BTC-USDT");

        assertThat(jdbcTemplate.sql).doesNotContain("i.contract_type = ?");
        assertThat(jdbcTemplate.args).containsExactly("BTC-USDT");
    }

    @Test
    void filtersRuleLookupByProductLineWhenProductTopicsAreEnabled() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        InstrumentRuleRepository repository = new InstrumentRuleRepository(jdbcTemplate, properties);

        repository.currentRule("BTC-USD-240927");

        assertThat(jdbcTemplate.sql).contains("i.symbol = ?").contains("i.contract_type = ?");
        assertThat(jdbcTemplate.args).containsExactly("BTC-USD-240927", "INVERSE_DELIVERY");
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
