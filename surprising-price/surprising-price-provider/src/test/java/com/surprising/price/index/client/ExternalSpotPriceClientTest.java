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
    void parsesBinanceWebSocketBookTicker() {
        ExternalSpotPriceClient client = new ExternalSpotPriceClient(new IndexPriceProperties(), new ObjectMapper());
        try {
            IndexPriceProperties.SourceConfig source = source("BINANCE", "BTCUSDT", "BINANCE_BOOK_TICKER");

            Optional<SourceQuote> quote = client.parseWebSocketPayload(source,
                    "{\"s\":\"BTCUSDT\",\"b\":\"100.00\",\"a\":\"102.00\",\"E\":1782828000000}",
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

            String payload = "{\"data\":[{\"instId\":\"BTC-USDT\",\"idxPx\":\"100.50\",\"ts\":\"1782828000000\"}]}";
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
}
