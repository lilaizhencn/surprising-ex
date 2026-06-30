package com.surprising.instrument.provider.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.IndexSourceConfig;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.api.model.RiskLimitBracket;
import java.math.BigDecimal;
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

    private InstrumentUpsertRequest request(List<IndexSourceConfig> sources, int minValidSources) {
        return new InstrumentUpsertRequest(
                "BTC-USDT", InstrumentType.PERPETUAL, ContractType.LINEAR_PERPETUAL, "BTC", "USDT", "USDT",
                new BigDecimal("1"), "USDT", new BigDecimal("0.1"), new BigDecimal("0.001"),
                new BigDecimal("0.001"), new BigDecimal("100"), new BigDecimal("5"),
                new BigDecimal("10000000"), 1, 3, List.of("LIMIT", "MARKET"), List.of("GTC", "IOC"),
                true, true, true, new BigDecimal("100"), new BigDecimal("0.01"),
                new BigDecimal("0.005"), new BigDecimal("5000000"), 8, new BigDecimal("0.0001"),
                new BigDecimal("0.003"), new BigDecimal("-0.003"), new BigDecimal("10000"),
                minValidSources, InstrumentStatus.TRADING, null,
                List.of(new RiskLimitBracket(1, BigDecimal.ZERO, new BigDecimal("50000"),
                        new BigDecimal("100"), new BigDecimal("0.01"), new BigDecimal("0.005"))),
                sources);
    }

    private IndexSourceConfig source(String name, boolean enabled) {
        return new IndexSourceConfig(name, enabled, "https://example.com", "/ticker", "BTCUSDT",
                "BINANCE_BOOK_TICKER", "USDT", "USDT", null, null, null, "DISCOUNT",
                "MULTIPLY", new BigDecimal("0.5"), true, "wss://example.com", "{}", "BINANCE_BOOK_TICKER",
                BigDecimal.ONE);
    }
}
