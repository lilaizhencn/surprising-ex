package com.surprising.candlestick.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Validates real persisted minute queries and rollups against init.sql. */
@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class CandleQueryPostgresTest {
    @Test
    void lateTradeReplacesMinuteAndRollupWhileDuplicateAndOlderVersionsCannotUndoIt() {
        var source = new DriverManagerDataSource(System.getenv("INSTRUMENT_TEST_JDBC_URL"),
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            var open = Instant.parse("2026-01-02T00:00:00Z");
            var old = minute(open, 1, "100", "1");
            var revised = minute(open, 2, "110", "3");
            var sink = new PostgresCandleSink(jdbc);
            sink.upsertBatch(java.util.List.of(old));
            sink.upsertBatch(java.util.List.of(revised));
            sink.upsertBatch(java.util.List.of(old));
            sink.upsertBatch(java.util.List.of(revised));
            var query = new CandleQueryRepository(jdbc);
            for (String period : java.util.List.of("1m", "5m", "15m", "1h", "1d", "1w")) {
                var row = query.findLatest("2147483598", period).orElseThrow();
                assertThat(row.closePrice()).isEqualByComparingTo("110");
                assertThat(row.baseVolume()).isEqualByComparingTo("3");
                assertThat(row.tradeCount()).isEqualTo(2);
            }
        });
    }

    @Test
    void earlierEventTimeTradeRaisesRevisionWithoutChangingTheLastTradeSequence() {
        var source = new DriverManagerDataSource(System.getenv("INSTRUMENT_TEST_JDBC_URL"),
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            var open = Instant.parse("2026-01-02T00:00:00Z");
            var old = minute(open, 2, "110", "3");
            var revised = minute(open, 3, "110", "4");
            revised.setLastSequence(old.getLastSequence());
            revised.setLastTradeId(old.getLastTradeId());
            revised.setHighPrice(new java.math.BigDecimal("120"));
            var sink = new PostgresCandleSink(jdbc);
            sink.upsertBatch(java.util.List.of(old, revised, old, revised));
            var row = new CandleQueryRepository(jdbc).findLatest("2147483598", "1m").orElseThrow();
            assertThat(row.tradeCount()).isEqualTo(3);
            assertThat(row.lastSequence()).isEqualTo(2);
            assertThat(row.closePrice()).isEqualByComparingTo("110");
            assertThat(row.highPrice()).isEqualByComparingTo("120");
            assertThat(row.baseVolume()).isEqualByComparingTo("4");
        });
    }

    private com.surprising.candlestick.provider.aggregation.CandleSnapshot minute(
            Instant open, long sequence, String close, String volume) {
        return new com.surprising.candlestick.provider.aggregation.CandleSnapshot("2147483598", "1m", open,
                open.plusSeconds(60), new java.math.BigDecimal("100"), new java.math.BigDecimal(close),
                new java.math.BigDecimal("100"), new java.math.BigDecimal(close), new java.math.BigDecimal(volume),
                new java.math.BigDecimal(volume).multiply(new java.math.BigDecimal(close)), sequence, "first", "last" + sequence,
                1L, sequence, com.surprising.candlestick.api.model.CandleStatus.CLOSED, open.plusSeconds(60 + sequence), 0, sequence);
    }

    @Test
    void readsMinuteAndRollupByPermanentIdWithoutMixingOtherMarkets() {
        var source = new DriverManagerDataSource(System.getenv("INSTRUMENT_TEST_JDBC_URL"),
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            jdbc.update("""
                    INSERT INTO candlestick_candles(instrument_id,period,open_time,close_time,
                      open_price,high_price,low_price,close_price,base_volume,quote_volume,trade_count,status,updated_at)
                    VALUES ('2147483600','1m','2026-01-01 00:00Z','2026-01-01 00:01Z',100,102,99,101,2,202,1,'CLOSED',now()),
                           ('2147483600','1m','2026-01-01 00:01Z','2026-01-01 00:02Z',101,103,100,102,3,306,1,'CLOSED',now()),
                           ('2147483601','1m','2026-01-01 00:01Z','2026-01-01 00:02Z',900,903,899,902,8,7216,1,'CLOSED',now())
                    """);
            var repository = new CandleQueryRepository(jdbc);
            var from = Instant.parse("2026-01-01T00:00:00Z");
            var to = Instant.parse("2026-01-01T00:15:00Z");
            var minutes = repository.findRange("2147483600", "1m", from, to, 1441);
            assertThat(minutes).hasSize(2).allSatisfy(row -> assertThat(row.instrumentId()).isEqualTo("2147483600"));
            assertThat(repository.findLatest("2147483600", "1m").orElseThrow().closePrice()).isEqualByComparingTo("102");
            assertThat(repository.findBefore("2147483600", from)).isEmpty();
            assertThat(repository.findBefore("2147483600", from.plusSeconds(60)).orElseThrow().closePrice()).isEqualByComparingTo("101");
            assertThat(repository.findBefore("2147483600", to).orElseThrow().closePrice()).isEqualByComparingTo("102");
            var rollup = repository.findRange("2147483600", "15m", from, to, 120);
            assertThat(rollup).hasSize(1);
            assertThat(rollup.getFirst().baseVolume()).isEqualByComparingTo("5");
            assertThat(rollup.getFirst().quoteVolume()).isEqualByComparingTo("508");
            assertThat(repository.findLatest("2147483600", "15m").orElseThrow().highPrice()).isEqualByComparingTo("103");
        });
    }
}
