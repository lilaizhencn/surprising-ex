package com.surprising.price.index.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.price.api.model.QuoteTransport;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.model.SourceQuote;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ExternalSpotPriceClientTest {

    @Test
    void parsesOfficialBinanceWebSocketTicker() {
        ExternalSpotPriceClient client = new ExternalSpotPriceClient(new IndexPriceProperties(), new ObjectMapper());
        try {
            IndexPriceProperties.SourceConfig source = source("BINANCE", "BTCUSDT", "BINANCE_BOOK_TICKER");

            Optional<SourceQuote> quote = client.parseWebSocketPayload(source,
                    "{\"e\":\"24hrTicker\",\"E\":1782828000000,\"s\":\"BTCUSDT\","
                            + "\"c\":\"101.00\",\"b\":\"100.00\",\"a\":\"102.00\"}",
                    Instant.parse("2026-06-30T10:00:00Z"));

            assertThat(quote).isPresent();
            assertThat(quote.get().price()).isEqualByComparingTo("101.00");
            assertThat(quote.get().sourceTime()).isEqualTo(Instant.ofEpochMilli(1782828000000L));
            assertThat(quote.get().transport()).isEqualTo(QuoteTransport.PUBLIC_WEBSOCKET);
        } finally {
            client.close();
        }
    }

    @Test
    void parsesOfficialBybitSpotWebSocketTickerWithSourceTime() {
        ExternalSpotPriceClient client = new ExternalSpotPriceClient(new IndexPriceProperties(), new ObjectMapper());
        try {
            IndexPriceProperties.SourceConfig source = source("BYBIT", "BTCUSDT", "BYBIT_TICKER");

            Optional<SourceQuote> quote = client.parseWebSocketPayload(source,
                    "{\"topic\":\"tickers.BTCUSDT\",\"ts\":1782828000000,\"type\":\"snapshot\","
                            + "\"data\":{\"symbol\":\"BTCUSDT\",\"lastPrice\":\"101.00\","
                            + "\"bid1Price\":\"100.00\",\"ask1Price\":\"102.00\"}}",
                    Instant.parse("2026-06-30T10:00:01Z"));

            assertThat(quote).isPresent();
            assertThat(quote.get().price()).isEqualByComparingTo("101.00");
            assertThat(quote.get().sourceTime()).isEqualTo(Instant.ofEpochMilli(1782828000000L));
            assertThat(quote.get().transport()).isEqualTo(QuoteTransport.PUBLIC_WEBSOCKET);
        } finally {
            client.close();
        }
    }

    @Test
    void rejectsMalformedAndMismatchedPublicWebSocketFrames() {
        ExternalSpotPriceClient client = new ExternalSpotPriceClient(new IndexPriceProperties(), new ObjectMapper());
        try {
            Instant receivedAt = Instant.parse("2026-06-30T10:00:01Z");

            assertThat(client.parseWebSocketPayload(
                    source("BINANCE", "BTCUSDT", "BINANCE_BOOK_TICKER"),
                    "{\"s\":\"ETHUSDT\",\"b\":\"100.00\",\"a\":\"102.00\"}", receivedAt)).isEmpty();
            assertThat(client.parseWebSocketPayload(
                    source("BYBIT", "BTCUSDT", "BYBIT_TICKER"),
                    "{\"topic\":\"tickers.BTCUSDT\",\"ts\":1782828000000,"
                            + "\"data\":{\"symbol\":\"ETHUSDT\",\"lastPrice\":\"101.00\"}}",
                    receivedAt)).isEmpty();
            assertThat(client.parseWebSocketPayload(
                    source("OKX", "BTC-USDT", "OKX_INDEX_TICKER"),
                    "{\"arg\":{\"channel\":\"index-tickers\",\"instId\":\"BTC-USDT\"},"
                            + "\"data\":[{\"instId\":\"ETH-USDT\",\"idxPx\":\"101.00\",\"ts\":\"1782828000000\"}]}",
                    receivedAt)).isEmpty();
            assertThat(client.parseWebSocketPayload(
                    source("BINANCE", "BTCUSDT", "BINANCE_BOOK_TICKER"), "{\"s\":\"BTCUSDT\"}", receivedAt)).isEmpty();
            assertThat(client.parseWebSocketPayload(
                    source("BYBIT", "BTCUSDT", "BYBIT_TICKER"),
                    "{\"topic\":\"tickers.BTCUSDT\",\"data\":{\"symbol\":\"BTCUSDT\"}}", receivedAt)).isEmpty();
            assertThat(client.parseWebSocketPayload(
                    source("OKX", "BTC-USDT", "OKX_INDEX_TICKER"),
                    "{\"arg\":{\"channel\":\"index-tickers\",\"instId\":\"BTC-USDT\"},"
                            + "\"data\":[{\"instId\":\"BTC-USDT\",\"idxPx\":\"\"}]}", receivedAt)).isEmpty();
        } finally {
            client.close();
        }
    }

    @Test
    void rejectsPublicWebSocketFramesWithoutConfiguredOfficialAncestry() {
        ExternalSpotPriceClient client = new ExternalSpotPriceClient(new IndexPriceProperties(), new ObjectMapper());
        try {
            Instant receivedAt = Instant.parse("2026-06-30T10:00:01Z");
            IndexPriceProperties.SourceConfig okx = source("OKX", "BTC-USDT", "OKX_INDEX_TICKER");
            IndexPriceProperties.SourceConfig bybit = source("BYBIT", "BTCUSDT", "BYBIT_TICKER");

            assertThat(client.parseWebSocketPayload(okx,
                    "{\"arg\":{\"channel\":\"tickers\",\"instId\":\"BTC-USDT\"},"
                            + "\"data\":[{\"instId\":\"BTC-USDT\",\"idxPx\":\"101.00\",\"ts\":\"1782828000000\"}]}",
                    receivedAt)).isEmpty();
            assertThat(client.parseWebSocketPayload(okx,
                    "{\"data\":[{\"instId\":\"BTC-USDT\",\"idxPx\":\"101.00\",\"ts\":\"1782828000000\"}]}",
                    receivedAt)).isEmpty();
            assertThat(client.parseWebSocketPayload(bybit,
                    "{\"topic\":\"orderbook.1.BTCUSDT\",\"ts\":1782828000000,"
                            + "\"data\":{\"symbol\":\"BTCUSDT\",\"lastPrice\":\"101.00\","
                            + "\"bid1Price\":\"100.00\",\"ask1Price\":\"102.00\"}}",
                    receivedAt)).isEmpty();
            assertThat(client.parseWebSocketPayload(bybit,
                    "{\"ts\":1782828000000,\"data\":{\"symbol\":\"BTCUSDT\",\"lastPrice\":\"101.00\","
                            + "\"bid1Price\":\"100.00\",\"ask1Price\":\"102.00\"}}",
                    receivedAt)).isEmpty();
        } finally {
            client.close();
        }
    }

    @Test
    void requiresExactOkxWebSocketInstrumentAncestryWithoutPunctuationStripping() {
        ExternalSpotPriceClient client = new ExternalSpotPriceClient(new IndexPriceProperties(), new ObjectMapper());
        try {
            Instant receivedAt = Instant.parse("2026-06-30T10:00:01Z");
            IndexPriceProperties.SourceConfig hyphenated = source("OKX", "BTC-USDT", "OKX_INDEX_TICKER");
            IndexPriceProperties.SourceConfig compact = source("OKX", "BTCUSDT", "OKX_INDEX_TICKER");
            IndexPriceProperties.SourceConfig underscored = source("OKX", "BTC_USDT", "OKX_INDEX_TICKER");

            assertThat(client.parseWebSocketPayload(compact, okxIndexTicker("BTC-USDT", "BTC-USDT", "101.00"), receivedAt)).isEmpty();
            assertThat(client.parseWebSocketPayload(hyphenated, okxIndexTicker("BTCUSDT", "BTCUSDT", "101.00"), receivedAt)).isEmpty();
            assertThat(client.parseWebSocketPayload(underscored, okxIndexTicker("BTC-USDT", "BTC-USDT", "101.00"), receivedAt)).isEmpty();
            assertThat(client.parseWebSocketPayload(hyphenated, okxIndexTicker("BTC-USDT", "ETH-USDT", "101.00"), receivedAt)).isEmpty();
            assertThat(client.parseWebSocketPayload(hyphenated, okxIndexTicker(null, "BTC-USDT", "101.00"), receivedAt)).isEmpty();
            assertThat(client.parseWebSocketPayload(hyphenated, okxIndexTicker("BTC-USDT", null, "101.00"), receivedAt)).isEmpty();
            assertThat(client.parseWebSocketPayload(hyphenated, okxIndexTicker("BTC-USDT", "BTC-USDT", ""), receivedAt)).isEmpty();
            assertThat(client.parseWebSocketPayload(hyphenated,
                    "{\"event\":\"subscribe\",\"arg\":{\"channel\":\"index-tickers\",\"instId\":\"BTC-USDT\"}}", receivedAt)).isEmpty();

            Optional<SourceQuote> canonical = client.parseWebSocketPayload(hyphenated,
                    okxIndexTicker("BTC-USDT", "BTC-USDT", "101.00"), receivedAt);
            Optional<SourceQuote> asciiCaseOnly = client.parseWebSocketPayload(hyphenated,
                    okxIndexTicker("btc-usdt", "btc-usdt", "102.00"), receivedAt);

            assertThat(canonical).isPresent();
            assertThat(canonical.get().sourceTime()).isEqualTo(Instant.ofEpochMilli(1782828000000L));
            assertThat(canonical.get().transport()).isEqualTo(QuoteTransport.PUBLIC_WEBSOCKET);
            assertThat(asciiCaseOnly).isPresent();
            assertThat(asciiCaseOnly.get().price()).isEqualByComparingTo("102.00");
        } finally {
            client.close();
        }
    }

    @Test
    void ignoresWebSocketPayloadForAnotherInstrument() {
        ExternalSpotPriceClient client = new ExternalSpotPriceClient(new IndexPriceProperties(), new ObjectMapper());
        try {
            IndexPriceProperties.SourceConfig source = source("BINANCE", "ETHUSDT", "BINANCE_BOOK_TICKER");

            Optional<SourceQuote> quote = client.parseWebSocketPayload(source,
                    "{\"s\":\"BTCUSDT\",\"b\":\"100.00\",\"a\":\"102.00\"}",
                    Instant.parse("2026-06-30T10:00:00Z"));

            assertThat(quote).isEmpty();
        } finally {
            client.close();
        }
    }

    @Test
    void parsesOkxIndexTickerForRestAndWebSocket() {
        ExternalSpotPriceClient client = new ExternalSpotPriceClient(new IndexPriceProperties(), new ObjectMapper());
        try {
            IndexPriceProperties.SourceConfig source = source("OKX", "BTC-USDT", "OKX_INDEX_TICKER");

            String payload = "{\"arg\":{\"channel\":\"index-tickers\",\"instId\":\"BTC-USDT\"},"
                    + "\"data\":[{\"instId\":\"BTC-USDT\",\"idxPx\":\"100.50\",\"ts\":\"1782828000000\"}]}";
            SourceQuote restQuote = client.parsePayload(source, payload,
                    Instant.parse("2026-06-30T10:00:00Z"), 1L);
            Optional<SourceQuote> websocketQuote = client.parseWebSocketPayload(source, payload,
                    Instant.parse("2026-06-30T10:00:00Z"));

            assertThat(restQuote.price()).isEqualByComparingTo("100.50");
            assertThat(restQuote.transport()).isEqualTo(QuoteTransport.REST);
            assertThat(websocketQuote).isPresent();
            assertThat(websocketQuote.get().price()).isEqualByComparingTo("100.50");
            assertThat(websocketQuote.get().sourceTime()).isEqualTo(Instant.ofEpochMilli(1782828000000L));
            assertThat(websocketQuote.get().transport()).isEqualTo(QuoteTransport.PUBLIC_WEBSOCKET);
        } finally {
            client.close();
        }
    }

    @Test
    void discountsUsdSourceWhenStableConversionIsUnavailable() {
        ExternalSpotPriceClient client = new ExternalSpotPriceClient(new IndexPriceProperties(), new ObjectMapper());
        try {
            IndexPriceProperties.SourceConfig source = source("COINBASE", "BTC-USD", "COINBASE_TICKER");
            source.setQuoteCurrency("USD");
            source.setTargetQuoteCurrency("USDT");
            source.setFallbackWeightMultiplier(new BigDecimal("0.25"));
            source.setWeight(new BigDecimal("2"));

            SourceQuote quote = client.parsePayload(source,
                    "{\"bid\":\"99.00\",\"ask\":\"101.00\",\"price\":\"100.00\",\"time\":\"2026-06-30T10:00:00Z\"}",
                    Instant.parse("2026-06-30T10:00:00Z"), 1L);

            assertThat(quote.price()).isEqualByComparingTo("100.00");
            assertThat(quote.configuredWeight()).isEqualByComparingTo("0.50");
            assertThat(quote.reason()).contains("conversion failed");
        } finally {
            client.close();
        }
    }

    private IndexPriceProperties.SourceConfig source(String name, String symbol, String parser) {
        IndexPriceProperties.SourceConfig source = new IndexPriceProperties.SourceConfig();
        source.setName(name);
        source.setSourceSymbol(symbol);
        source.setParser(parser);
        source.setWebsocketParser(parser);
        source.setWeight(BigDecimal.ONE);
        return source;
    }

    private String okxIndexTicker(String argInstId, String dataInstId, String indexPrice) {
        String arg = argInstId == null ? "{\"channel\":\"index-tickers\"}"
                : "{\"channel\":\"index-tickers\",\"instId\":\"" + argInstId + "\"}";
        String data = dataInstId == null ? "{\"idxPx\":\"" + indexPrice + "\",\"ts\":\"1782828000000\"}"
                : "{\"instId\":\"" + dataInstId + "\",\"idxPx\":\"" + indexPrice
                        + "\",\"ts\":\"1782828000000\"}";
        return "{\"arg\":" + arg + ",\"data\":[" + data + "]}";
    }
}
