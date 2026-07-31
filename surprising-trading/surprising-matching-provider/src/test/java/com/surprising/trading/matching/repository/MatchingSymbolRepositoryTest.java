package com.surprising.trading.matching.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.service.MatchingSymbolService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MatchingSymbolRepositoryTest {

    @Test
    void leavesTradingSymbolLookupUnfilteredForLegacyTopics() {
        MatchingProperties properties = new MatchingProperties();
        InstrumentSnapshotCache cache = cache(ProductLine.LINEAR_PERPETUAL,
                instrument("BTC-USDT", 1L), instrument("BTC-USDT", 2L));
        MatchingSymbolService service = service(properties, cache);

        assertThat(service.currentTradingSymbols())
                .extracting(com.surprising.trading.matching.model.InstrumentSymbol::symbol)
                .containsExactly("BTC-USDT");
    }

    @Test
    void filtersTradingSymbolsByProductLineWhenProductTopicsAreEnabled() {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingSymbolService service = service(properties, cache(ProductLine.LINEAR_DELIVERY,
                instrument("BTC-USDT-240927", 7L, ContractType.LINEAR_DELIVERY)));

        assertThat(service.currentTradingSymbols()).hasSize(1);
    }

    @Test
    void filtersSingleSymbolLookupByProductLineWhenProductTopicsAreEnabled() {
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingSymbolService service = service(properties,
                cache(ProductLine.INVERSE_DELIVERY,
                        instrument("BTC-USD-240927", 7L, ContractType.INVERSE_DELIVERY)));

        assertThat(service.currentTradingSymbol("BTC-USD-240927")).isPresent();
    }

    @Test
    void findsMatchingSymbolWithinCurrentProductLine() {
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate();
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.SPOT);
        properties.getKafka().setProductTopicsEnabled(true);
        MatchingSymbolRepository repository = new MatchingSymbolRepository(jdbcTemplate, properties);

        repository.find("BTC-USDT");

        assertThat(jdbcTemplate.sql).contains("product_line = ?").contains("symbol = ?");
        assertThat(jdbcTemplate.args).containsExactly("SPOT", "BTC-USDT");
    }

    private MatchingSymbolService service(MatchingProperties properties, InstrumentSnapshotCache cache) {
        return new MatchingSymbolService(
                null, null, null, properties, cache);
    }

    private InstrumentSnapshotCache cache(ProductLine productLine, InstrumentResponse... instruments) {
        InstrumentSnapshotCache cache = new InstrumentSnapshotCache();
        cache.replace(productLine, List.of(instruments));
        return cache;
    }

    private InstrumentResponse instrument(String symbol, long version) {
        return instrument(symbol, version, ContractType.LINEAR_PERPETUAL);
    }

    private InstrumentResponse instrument(String symbol, long version, ContractType contractType) {
        Instant now = Instant.parse("2026-07-31T00:00:00Z");
        return new InstrumentResponse(symbol, version, InstrumentType.PERPETUAL, contractType,
                "BTC", "USDT", "USDT", 1_000_000L, "BTC", 10L, 1L, 1L, 1_000_000L,
                1L, 1_000_000_000L, 1L, 2, 0, List.of("LIMIT"), List.of("GTC"), true,
                true, true, 100_000_000L, 10_000L, 5_000L, 100L, 500L,
                1_000_000_000L, 300_000L, 250_000_000L, 8, 100L, 3_000L, -3_000L,
                10_000_000L, 3, null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, now, now, now, List.of(), List.of());
    }

    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] args = new Object[0];

        @Override
        public <T> List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> rowMapper, Object... args) {
            this.sql = sql;
            this.args = args == null ? new Object[0] : args;
            return List.of();
        }
    }
}
