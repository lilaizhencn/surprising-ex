package com.surprising.price.index.client;

import com.surprising.price.index.config.IndexPriceProperties;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;
import tools.jackson.databind.json.JsonMapper;

/** The same parse-once/three-source filter used by the shared public WebSocket. */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Threads(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 3, time = 2)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-XX:+UseZGC"})
public class SharedPriceMessageBenchmark {
    ExternalSpotPriceClient client;
    IndexPriceProperties.SourceConfig[] sources;
    final Instant now = Instant.parse("2026-10-08T00:00:00Z");
    final String payload = "{\"s\":\"ETHUSDT\",\"b\":\"2500.01\",\"a\":\"2500.02\"}";
    @Setup public void setup() {
        client = new ExternalSpotPriceClient(new IndexPriceProperties(), JsonMapper.builder().build());
        sources = new IndexPriceProperties.SourceConfig[3];
        String[] symbols = {"BTCUSDT", "ETHUSDT", "SOLUSDT"};
        for (int i = 0; i < 3; i++) {
            var source = new IndexPriceProperties.SourceConfig();
            source.setName("BINANCE"); source.setParser("BINANCE_BOOK_TICKER"); source.setSourceSymbol(symbols[i]);
            sources[i] = source;
        }
    }
    @Benchmark public int parseAndRoute() {
        var message = client.parseWebSocketMessage(payload);
        int matched = 0;
        for (var source : sources) if (client.parseWebSocketPayload(source, message, now).isPresent()) matched++;
        if (matched != 1) throw new IllegalStateException("shared source routing incorrect");
        return matched;
    }
    @TearDown public void close() { client.close(); }
}
