package com.surprising.marketmaker.provider.repository;

import com.surprising.marketmaker.provider.model.MarketMakerStrategyDefinition;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Actual committed cache reads. JDBC fixture loads happen only in setup, never in measurement. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseZGC"})
public class MakerConfigurationReadBenchmark {
    JdbcMarketMakerStrategyOverrideStore store;
    int queries;

    @Setup public void setup() {
        var rows = java.util.stream.IntStream.range(0, 3).mapToObj(i -> new MarketMakerStrategyDefinition(
                "maker-" + i, ProductLine.LINEAR_PERPETUAL, true, List.of(2L), List.of("604"),
                10, MarginMode.CROSS, 1, 1, 1000, 0, 3, 0, 1)).toList();
        store = new JdbcMarketMakerStrategyOverrideStore(new JdbcTemplate() {
            @Override @SuppressWarnings("unchecked") public <T> List<T> query(String sql, RowMapper<T> mapper) {
                queries++;
                return sql.contains("FROM market_maker_strategies") ? (List<T>) rows : List.of();
            }
        });
        store.definitions();
        store.findAll();
    }

    @Benchmark public int committedConfigurationRead() {
        return store.definitions().size() + (store.find(ProductLine.LINEAR_PERPETUAL, "maker-2").isPresent() ? 1 : 0);
    }

    @TearDown public void verify() {
        if (queries != 2) throw new IllegalStateException("configuration reads reached JDBC during measurement");
    }
}
