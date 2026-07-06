package com.surprising.trading.matching.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.matching.config.MatchingProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class MatchingSymbolRepositoryTest {

    @Test
    void leavesTradingSymbolLookupUnfilteredForLegacyTopics() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        MatchingSymbolRepository repository = new MatchingSymbolRepository(jdbcTemplate, null);

        repository.currentTradingSymbols();

        assertThat(jdbcTemplate.sql).doesNotContain("i.contract_type = ?");
        assertThat(jdbcTemplate.args).isEmpty();
    }

    @Test
    void filtersTradingSymbolsByProductLineWhenProductTopicsAreEnabled() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingSymbolRepository repository = new MatchingSymbolRepository(jdbcTemplate, null, properties);

        repository.currentTradingSymbols();

        assertThat(jdbcTemplate.sql).contains("i.contract_type = ?");
        assertThat(jdbcTemplate.args).containsExactly("LINEAR_DELIVERY");
    }

    @Test
    void filtersSingleSymbolLookupByProductLineWhenProductTopicsAreEnabled() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingSymbolRepository repository = new MatchingSymbolRepository(jdbcTemplate, null, properties);

        repository.currentTradingSymbol("BTC-USD-240927");

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
