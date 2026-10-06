package com.surprising.instrument.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class MarketSummaryPostgresTest {
    @Test
    void readsRealOpeningCloseAndQuietWindowWithoutInventingVolume() {
        var source = new DriverManagerDataSource(System.getenv("INSTRUMENT_TEST_JDBC_URL"),
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            int id = jdbc.queryForObject("SELECT min(instrument_id) FROM instruments", Integer.class);
            jdbc.update("DELETE FROM candlestick_candles WHERE instrument_id = ?", String.valueOf(id));
            jdbc.update("""
                    INSERT INTO candlestick_candles(instrument_id,period,open_time,close_time,
                      open_price,high_price,low_price,close_price,base_volume,quote_volume,trade_count,status,updated_at)
                    VALUES (?,'1m','2026-01-01 00:00Z','2026-01-01 00:01Z',100,100,100,100,9,900,1,'CLOSED',now()),
                           (?,'1m','2026-01-02 12:00Z','2026-01-02 12:01Z',105,105,105,105,2,210,1,'CLOSED',now())
                    """, String.valueOf(id), String.valueOf(id));
            var repository = new MarketSummaryRepository(new NamedParameterJdbcTemplate(source));
            var active = repository.summaries(List.of(id), Instant.parse("2026-01-03T00:00:00Z")).get(id);
            assertThat(active.change24h()).isEqualByComparingTo("5");
            assertThat(active.volume24h()).isEqualByComparingTo("2");
            assertThat(active.quoteVolume24h()).isEqualByComparingTo("210");
            var quiet = repository.summaries(List.of(id), Instant.parse("2026-01-05T00:00:00Z")).get(id);
            assertThat(quiet.lastPrice()).isEqualByComparingTo("105");
            assertThat(quiet.change24h()).isZero();
            assertThat(quiet.volume24h()).isZero();
            assertThat(quiet.trend()).hasSize(2);
        });
    }
}
