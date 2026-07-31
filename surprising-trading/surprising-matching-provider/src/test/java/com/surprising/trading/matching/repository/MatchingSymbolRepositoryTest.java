package com.surprising.trading.matching.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.matching.config.MatchingProperties;
import com.surprising.trading.matching.repository.MatchingInstrumentRepository.InstrumentVersion;
import com.surprising.trading.matching.service.MatchingSymbolService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class MatchingSymbolRepositoryTest {

    @Test
    void leavesTradingSymbolLookupUnfilteredForLegacyTopics() {
        MatchingInstrumentRepository instrumentRepository = mock(MatchingInstrumentRepository.class);
        MatchingInstrumentCurrentVersionRepository currentVersionRepository =
                mock(MatchingInstrumentCurrentVersionRepository.class);
        when(currentVersionRepository.findAll()).thenReturn(Map.of("BTC-USDT", 2L));
        when(instrumentRepository.findTradingVersions(null)).thenReturn(List.of(
                instrument("BTC-USDT", 1L),
                instrument("BTC-USDT", 2L)));
        MatchingSymbolService service = service(
                instrumentRepository, currentVersionRepository, new MatchingProperties());

        assertThat(service.currentTradingSymbols())
                .extracting(com.surprising.trading.matching.model.InstrumentSymbol::symbol)
                .containsExactly("BTC-USDT");
        verify(instrumentRepository).findTradingVersions(null);
    }

    @Test
    void filtersTradingSymbolsByProductLineWhenProductTopicsAreEnabled() {
        MatchingInstrumentRepository instrumentRepository = mock(MatchingInstrumentRepository.class);
        MatchingInstrumentCurrentVersionRepository currentVersionRepository =
                mock(MatchingInstrumentCurrentVersionRepository.class);
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        when(currentVersionRepository.findAll()).thenReturn(Map.of());
        MatchingSymbolService service = service(instrumentRepository, currentVersionRepository, properties);

        service.currentTradingSymbols();

        verify(instrumentRepository).findTradingVersions("LINEAR_DELIVERY");
    }

    @Test
    void filtersSingleSymbolLookupByProductLineWhenProductTopicsAreEnabled() {
        MatchingInstrumentRepository instrumentRepository = mock(MatchingInstrumentRepository.class);
        MatchingInstrumentCurrentVersionRepository currentVersionRepository =
                mock(MatchingInstrumentCurrentVersionRepository.class);
        MatchingProperties properties = new MatchingProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        when(currentVersionRepository.findVersion("BTC-USD-240927")).thenReturn(Optional.of(7L));
        when(instrumentRepository.findTrading("BTC-USD-240927", 7L, "INVERSE_DELIVERY"))
                .thenReturn(Optional.of(instrument("BTC-USD-240927", 7L)));
        MatchingSymbolService service = service(instrumentRepository, currentVersionRepository, properties);

        assertThat(service.currentTradingSymbol("BTC-USD-240927")).isPresent();
        verify(instrumentRepository).findTrading("BTC-USD-240927", 7L, "INVERSE_DELIVERY");
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

    private MatchingSymbolService service(MatchingInstrumentRepository instrumentRepository,
                                          MatchingInstrumentCurrentVersionRepository currentVersionRepository,
                                          MatchingProperties properties) {
        return new MatchingSymbolService(
                instrumentRepository, currentVersionRepository, null, null, null, properties);
    }

    private InstrumentVersion instrument(String symbol, long version) {
        return new InstrumentVersion(symbol, version, "BTC", "USDT", "USDT");
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
