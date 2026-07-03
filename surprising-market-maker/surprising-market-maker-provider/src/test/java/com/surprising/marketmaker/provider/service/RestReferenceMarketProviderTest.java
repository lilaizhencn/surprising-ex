package com.surprising.marketmaker.provider.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RestReferenceMarketProviderTest {

    @Test
    void parsesBinanceDepthIntoTicksAndSteps() {
        RestReferenceMarketProvider provider = provider();

        var snapshot = provider.parsePayload(source("BINANCE_DEPTH"), "BTC-USDT", instrument(), """
                {"lastUpdateId":1,"bids":[["60000.1","0.015"],["59999.9","0.020"]],
                 "asks":[["60000.3","0.017"],["60000.5","0.025"]]}
                """, Instant.parse("2026-07-04T00:00:00Z"));

        assertThat(snapshot.source()).isEqualTo("BINANCE");
        assertThat(snapshot.bids()).extracting(level -> level.priceTicks()).containsExactly(600_001L, 599_999L);
        assertThat(snapshot.bids()).extracting(level -> level.quantitySteps()).containsExactly(15L, 20L);
        assertThat(snapshot.asks()).extracting(level -> level.priceTicks()).containsExactly(600_003L, 600_005L);
        assertThat(snapshot.asks()).extracting(level -> level.quantitySteps()).containsExactly(17L, 25L);
    }

    @Test
    void parsesOkxBooksIntoTicksAndSteps() {
        RestReferenceMarketProvider provider = provider();

        var snapshot = provider.parsePayload(source("OKX_BOOKS"), "BTC-USDT", instrument(), """
                {"code":"0","data":[{"bids":[["60000.1","0.015","0","1"]],
                 "asks":[["60000.3","0.017","0","1"]]}]}
                """, Instant.parse("2026-07-04T00:00:00Z"));

        assertThat(snapshot.bids()).singleElement()
                .satisfies(level -> {
                    assertThat(level.priceTicks()).isEqualTo(600_001L);
                    assertThat(level.quantitySteps()).isEqualTo(15L);
                });
        assertThat(snapshot.asks()).singleElement()
                .satisfies(level -> {
                    assertThat(level.priceTicks()).isEqualTo(600_003L);
                    assertThat(level.quantitySteps()).isEqualTo(17L);
                });
    }

    @Test
    void parsesBybitOrderBookIntoTicksAndSteps() {
        RestReferenceMarketProvider provider = provider();

        var snapshot = provider.parsePayload(source("BYBIT_ORDERBOOK"), "BTC-USDT", instrument(), """
                {"retCode":0,"result":{"s":"BTCUSDT","b":[["60000.1","0.015"]],
                 "a":[["60000.3","0.017"]]}}
                """, Instant.parse("2026-07-04T00:00:00Z"));

        assertThat(snapshot.bids()).singleElement()
                .satisfies(level -> assertThat(level.priceTicks()).isEqualTo(600_001L));
        assertThat(snapshot.asks()).singleElement()
                .satisfies(level -> assertThat(level.quantitySteps()).isEqualTo(17L));
    }

    @Test
    void parsesBinanceWebSocketDepthStreamIntoLiveBook() {
        RestReferenceMarketProvider provider = provider();
        MarketMakerProperties.ReferenceMarket.Source source = source("BINANCE_DEPTH");
        source.setWebSocketParser("BINANCE_DEPTH_STREAM");

        var snapshot = provider.parseWebSocketPayload(source, "BTC-USDT", instrument(), """
                {"e":"depthUpdate","E":1,"b":[["60000.1","0.015"],["59999.9","0.020"]],
                 "a":[["60000.3","0.017"],["60000.5","0.025"]]}
                """, Instant.parse("2026-07-04T00:00:00Z"));

        assertThat(snapshot.bids()).extracting(level -> level.priceTicks()).containsExactly(600_001L, 599_999L);
        assertThat(snapshot.asks()).extracting(level -> level.quantitySteps()).containsExactly(17L, 25L);
    }

    @Test
    void appliesOkxWebSocketDeltaToLocalBook() {
        RestReferenceMarketProvider provider = provider();
        MarketMakerProperties.ReferenceMarket.Source source = source("OKX_BOOKS");
        source.setWebSocketParser("OKX_BOOKS_WS");

        provider.parseWebSocketPayload(source, "BTC-USDT", instrument(), """
                {"arg":{"channel":"books","instId":"BTC-USDT-SWAP"},"action":"snapshot",
                 "data":[{"bids":[["60000.1","0.015","0","1"],["59999.9","0.020","0","1"]],
                 "asks":[["60000.3","0.017","0","1"]]}]}
                """, Instant.parse("2026-07-04T00:00:00Z"));

        var snapshot = provider.parseWebSocketPayload(source, "BTC-USDT", instrument(), """
                {"arg":{"channel":"books","instId":"BTC-USDT-SWAP"},"action":"update",
                 "data":[{"bids":[["60000.1","0","0","0"],["60000.0","0.025","0","1"]],
                 "asks":[["60000.3","0.018","0","1"]]}]}
                """, Instant.parse("2026-07-04T00:00:01Z"));

        assertThat(snapshot.bids()).extracting(level -> level.priceTicks()).containsExactly(600_000L, 599_999L);
        assertThat(snapshot.bids()).extracting(level -> level.quantitySteps()).containsExactly(25L, 20L);
        assertThat(snapshot.asks()).singleElement()
                .satisfies(level -> assertThat(level.quantitySteps()).isEqualTo(18L));
    }

    @Test
    void parsesBybitWebSocketOrderBookSnapshot() {
        RestReferenceMarketProvider provider = provider();
        MarketMakerProperties.ReferenceMarket.Source source = source("BYBIT_ORDERBOOK");
        source.setWebSocketParser("BYBIT_ORDERBOOK_WS");

        var snapshot = provider.parseWebSocketPayload(source, "BTC-USDT", instrument(), """
                {"topic":"orderbook.50.BTCUSDT","type":"snapshot",
                 "data":{"s":"BTCUSDT","b":[["60000.1","0.015"]],"a":[["60000.3","0.017"]]}}
                """, Instant.parse("2026-07-04T00:00:00Z"));

        assertThat(snapshot.bids()).singleElement()
                .satisfies(level -> assertThat(level.quantitySteps()).isEqualTo(15L));
        assertThat(snapshot.asks()).singleElement()
                .satisfies(level -> assertThat(level.priceTicks()).isEqualTo(600_003L));
    }

    private RestReferenceMarketProvider provider() {
        MarketMakerProperties properties = new MarketMakerProperties();
        properties.getReferenceMarket().setEnabled(true);
        properties.getReferenceMarket().setDepthLevels(5);
        properties.getReferenceMarket().setMinQuantitySteps(1L);
        properties.getReferenceMarket().setMaxQuantitySteps(10_000L);
        return new RestReferenceMarketProvider(properties, new ObjectMapper());
    }

    private MarketMakerProperties.ReferenceMarket.Source source(String parser) {
        MarketMakerProperties.ReferenceMarket.Source source = new MarketMakerProperties.ReferenceMarket.Source();
        source.setName("BINANCE");
        source.setSymbol("BTC-USDT");
        source.setExternalSymbol("BTCUSDT");
        source.setUrl("https://example.invalid/depth?symbol={symbol}");
        source.setParser(parser);
        return source;
    }

    private InstrumentResponse instrument() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return new InstrumentResponse("BTC-USDT", 1L, InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL,
                "BTC", "USDT", "USDT", 1_000_000L, "BTC", 10_000_000L, 100_000L, 1L,
                1_000_000L, 1L, 1_000_000_000_000L, 1L, 1, 3, List.of("LIMIT"), List.of("GTX"),
                true, true, true, 100_000_000L, 10_000L, 5_000L, -100L, 500L,
                1_000_000_000L, 300_000L, 250_000_000L, 8, 100L, 3_000L, -3_000L,
                10_000_000L, 3, InstrumentStatus.TRADING, now, now, now, List.of(), List.of());
    }
}
