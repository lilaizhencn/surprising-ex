package com.surprising.price.index.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class IndexInstrumentConfigServiceTest {

    @Test
    void legacyInstrumentSnapshotLoadsPerpetualContracts() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getInstrument().setFallbackToStaticSymbols(false);

        new IndexInstrumentConfigService(properties, jdbcTemplate).refresh();

        assertThat(jdbcTemplate.sql).contains("i.instrument_type = 'PERPETUAL'");
        assertThat(jdbcTemplate.sql).doesNotContain("i.contract_type = ?");
        assertThat(jdbcTemplate.args).isEmpty();
    }

    @Test
    void productInstrumentSnapshotLoadsConfiguredContractType() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        IndexPriceProperties properties = new IndexPriceProperties();
        properties.getInstrument().setFallbackToStaticSymbols(false);
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);

        new IndexInstrumentConfigService(properties, jdbcTemplate).refresh();

        assertThat(jdbcTemplate.sql).contains("i.contract_type = ?");
        assertThat(jdbcTemplate.args).containsExactly("LINEAR_DELIVERY");
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] args = new Object[0];

        @Override
        public void query(String sql, Object[] args, RowCallbackHandler rch) throws DataAccessException {
            this.sql = sql;
            this.args = args;
        }
    }
}
