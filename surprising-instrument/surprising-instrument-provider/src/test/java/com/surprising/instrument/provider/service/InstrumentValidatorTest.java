package com.surprising.instrument.provider.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.IndexSourceConfig;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.api.model.RiskLimitBracket;
import java.util.List;
import org.junit.jupiter.api.Test;

class InstrumentValidatorTest {

    private final InstrumentValidator validator = new InstrumentValidator();

    @Test
    void rejectsTooFewEnabledIndexSources() {
        InstrumentUpsertRequest request = request(List.of(source("A", true), source("B", false)), 2);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enabled index sources");
    }

    @Test
    void acceptsSpotInstrumentWithoutPerpetualRiskAndIndexSources() {
        InstrumentUpsertRequest request = spotRequest();

        assertThatCode(() -> validator.validate(request))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSpotInstrumentWithPerpetualContractType() {
        InstrumentUpsertRequest request = new InstrumentUpsertRequest(
                "BTC-USDT-SPOT", InstrumentType.SPOT, ContractType.LINEAR_PERPETUAL, "BTC", "USDT", "USDT",
                1_000_000L, "USDT", 10_000_000L, 100_000L,
                1L, 100_000L, 500_000_000L, 1_000_000_000_000_000L, 10_000L,
                1, 3, List.of("LIMIT"), List.of("GTC", "IOC"),
                true, false, false, 1_000_000L, 1_000_000L,
                1L, 200L, 500L, 1L,
                0L, 1L, 1, 0L,
                0L, 0L, 1L,
                1, InstrumentStatus.TRADING, null, List.of(), List.of());

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SPOT instruments must use SPOT contractType");
    }

    @Test
    void rejectsSpotInstrumentWithReduceOnlyEnabled() {
        InstrumentUpsertRequest request = new InstrumentUpsertRequest(
                "BTC-USDT-SPOT", InstrumentType.SPOT, ContractType.SPOT, "BTC", "USDT", "USDT",
                1_000_000L, "USDT", 10_000_000L, 100_000L,
                1L, 100_000L, 500_000_000L, 1_000_000_000_000_000L, 10_000L,
                1, 3, List.of("LIMIT"), List.of("GTC", "IOC"),
                true, true, false, 1_000_000L, 1_000_000L,
                1L, 200L, 500L, 1L,
                0L, 1L, 1, 0L,
                0L, 0L, 1L,
                1, InstrumentStatus.TRADING, null, List.of(), List.of());

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spot instruments cannot enable reduce-only");
    }

    private InstrumentUpsertRequest request(List<IndexSourceConfig> sources, int minValidSources) {
        return new InstrumentUpsertRequest(
                "BTC-USDT", InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL, "BTC", "USDT", "USDT",
                1_000_000L, "USDT", 10_000_000L, 100_000L,
                1L, 100_000L, 500_000_000L, 1_000_000_000_000_000L, 10_000L,
                1, 3, List.of("LIMIT", "MARKET"), List.of("GTC", "IOC"),
                true, true, true, 100_000_000L, 10_000L,
                5_000L, 200L, 500L, 500_000_000_000_000L,
                300_000L, 25_000_000_000_000L, 8, 100L,
                3_000L, -3_000L, 1_000_000_000_000L,
                minValidSources, InstrumentStatus.TRADING, null,
                List.of(new RiskLimitBracket(1, 0L, 5_000_000_000_000L,
                        100_000_000L, 10_000L, 5_000L)),
                sources);
    }

    private InstrumentUpsertRequest spotRequest() {
        return new InstrumentUpsertRequest(
                "BTC-USDT-SPOT", InstrumentType.SPOT, ContractType.SPOT, "BTC", "USDT", "USDT",
                1_000_000L, "USDT", 10_000_000L, 100_000L,
                1L, 100_000L, 500_000_000L, 1_000_000_000_000_000L, 10_000L,
                1, 3, List.of("LIMIT"), List.of("GTC", "IOC"),
                true, false, false, 1_000_000L, 1_000_000L,
                1L, 200L, 500L, 1L,
                0L, 1L, 1, 0L,
                0L, 0L, 1L,
                1, InstrumentStatus.TRADING, null, List.of(), List.of());
    }

    private IndexSourceConfig source(String name, boolean enabled) {
        return new IndexSourceConfig(name, enabled, "https://example.com", "/ticker", "BTCUSDT",
                "BINANCE_BOOK_TICKER", "USDT", "USDT", null, null, null, "DISCOUNT",
                "MULTIPLY", 500_000L, true, "wss://example.com", "{}", "BINANCE_BOOK_TICKER",
                1_000_000L);
    }
}
