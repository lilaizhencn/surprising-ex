package com.surprising.candlestick.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.candlestick.provider.config.CandlestickProperties;
import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class SymbolRegistryServiceTest {

    @Test
    void strictInstrumentRegistryUsesLegacyPerpetualFilterByDefault() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        CandlestickProperties properties = new CandlestickProperties();
        properties.getSymbols().setAcceptUnknownSymbols(false);
        SymbolRegistryService service = new SymbolRegistryService(properties, jdbcTemplate);

        service.refresh();

        assertThat(jdbcTemplate.sql).contains("i.instrument_type = 'PERPETUAL'");
        assertThat(jdbcTemplate.sql).doesNotContain("i.contract_type =");
    }

    @Test
    void strictInstrumentRegistryFiltersByProductLineWhenProductTopicsAreEnabled() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        CandlestickProperties properties = new CandlestickProperties();
        properties.getSymbols().setAcceptUnknownSymbols(false);
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        SymbolRegistryService service = new SymbolRegistryService(properties, jdbcTemplate);

        service.refresh();

        assertThat(jdbcTemplate.sql).contains("i.contract_type = 'LINEAR_DELIVERY'");
        assertThat(jdbcTemplate.sql).doesNotContain("i.instrument_type = 'PERPETUAL'");
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String sql;

        @Override
        public void query(String sql, RowCallbackHandler rch) {
            this.sql = sql;
        }
    }
}
