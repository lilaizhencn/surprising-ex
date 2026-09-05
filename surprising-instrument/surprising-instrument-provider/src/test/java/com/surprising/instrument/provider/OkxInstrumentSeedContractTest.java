package com.surprising.instrument.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OkxInstrumentSeedContractTest {

    private static final String MATRIX_MARKER = "-- BTC-USDT-SWAP three-source public WebSocket matrix";

    @Test
    void linearPerpetualBtcSwapHasCanonicalThreeSourcePublicWebSocketMatrix() throws IOException {
        String sql = Files.readString(repositoryFile("okx-instrument-seed.sql"));

        assertThat(sql).contains(MATRIX_MARKER);
        int matrixStart = sql.indexOf(MATRIX_MARKER);
        String matrix = sql.substring(matrixStart, sql.indexOf("-- OKX catalog counts", matrixStart));
        assertThat(matrix)
                .contains("UPDATE instruments SET min_valid_index_sources = 3")
                .contains("WHERE contract_type = 'LINEAR_PERPETUAL' AND symbol = 'BTC-USDT-SWAP' AND version = 1")
                .contains("'OKX', TRUE", "'BINANCE', TRUE", "'BYBIT', TRUE")
                .contains("'wss://ws.okx.com:8443/ws/v5/public'")
                .contains("'wss://stream.binance.com:9443/ws'")
                .contains("'wss://stream.bybit.com/v5/public/spot'")
                .contains("'{\"op\":\"subscribe\",\"args\":[{\"channel\":\"index-tickers\",\"instId\":\"BTC-USDT\"}]}'")
                .contains("'{\"method\":\"SUBSCRIBE\",\"params\":[\"btcusdt@ticker\"],\"id\":1}'")
                .contains("'{\"op\":\"subscribe\",\"args\":[\"tickers.BTCUSDT\"]}'")
                .contains("'OKX_INDEX_TICKER'", "'BINANCE_BOOK_TICKER'", "'BYBIT_TICKER'");
        assertThat(matrix).doesNotContain("INVERSE_PERPETUAL", "LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION");
    }

    @Test
    void linearPerpetualBtcSwapUsesOneTenthUsdtPriceTicks() throws IOException {
        String sql = Files.readString(repositoryFile("okx-instrument-seed.sql"));

        assertThat(sql).contains("""
                UPDATE surprising_okx_instruments
                   SET price_tick_units = 10000000
                 WHERE product_line = 'LINEAR_PERPETUAL'
                   AND symbol = 'BTC-USDT-SWAP';
                """);
    }

    private Path repositoryFile(String fileName) {
        Path directory = Path.of("").toAbsolutePath();
        while (directory != null && !Files.exists(directory.resolve(fileName))) {
            directory = directory.getParent();
        }
        assertThat(directory).as("repository root containing %s", fileName).isNotNull();
        return directory.resolve(fileName);
    }
}
