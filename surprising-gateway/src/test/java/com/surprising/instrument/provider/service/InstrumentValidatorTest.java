package com.surprising.instrument.provider.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.surprising.instrument.api.model.ContractSettlementMethod;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.IndexSourceConfig;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.InstrumentType;
import com.surprising.instrument.api.model.InstrumentUpsertRequest;
import com.surprising.instrument.api.model.OptionExerciseStyle;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.instrument.api.model.RiskLimitBracket;
import java.time.Instant;
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
                1, null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, null, List.of(), List.of());

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
                0L, 1L, 0, 0L,
                0L, 0L, 1L,
                1, null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, null, List.of(), List.of());

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spot instruments cannot enable reduce-only");
    }

    @Test
    void acceptsDeliveryInstrumentWithExpiryMetadata() {
        InstrumentUpsertRequest request = deliveryRequest();

        assertThatCode(() -> validator.validate(request))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsOptionInstrumentWithOptionMetadata() {
        InstrumentUpsertRequest request = optionRequest();

        assertThatCode(() -> validator.validate(request))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsDeliveryInstrumentWithPerpetualContractType() {
        InstrumentUpsertRequest request = expiringDerivativeRequest("BTC-USDT-260327",
                InstrumentType.DELIVERY, ContractType.LINEAR_PERPETUAL, null, null, null,
                null, null, ContractSettlementMethod.CASH);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DELIVERY instruments must use a delivery contractType");
    }

    @Test
    void rejectsDeliveryInstrumentWithoutExpiryTime() {
        InstrumentUpsertRequest request = expiringDerivativeRequest("BTC-USDT-260327",
                InstrumentType.DELIVERY, ContractType.LINEAR_DELIVERY, null, expiry().plusSeconds(300),
                null, null, null, ContractSettlementMethod.CASH);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DELIVERY instruments require expiryTime");
    }

    @Test
    void rejectsOptionInstrumentWithoutStrikePrice() {
        InstrumentUpsertRequest request = expiringDerivativeRequest("BTC-USDT-260327-50000-C",
                InstrumentType.OPTION, ContractType.VANILLA_OPTION, expiry(), expiry().plusSeconds(300),
                "BTC-USDT", null, OptionType.CALL, ContractSettlementMethod.CASH);

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strikePriceUnits must be positive");
    }

    @Test
    void rejectsNonPerpetualFundingSettings() {
        InstrumentUpsertRequest request = new InstrumentUpsertRequest(
                "BTC-USDT-SPOT", InstrumentType.SPOT, ContractType.SPOT, "BTC", "USDT", "USDT",
                1_000_000L, "USDT", 10_000_000L, 100_000L,
                1L, 100_000L, 500_000_000L, 1_000_000_000_000_000L, 10_000L,
                1, 3, List.of("LIMIT"), List.of("GTC", "IOC"),
                true, false, false, 1_000_000L, 1_000_000L,
                1L, 200L, 500L, 1L,
                0L, 1L, 8, 100L,
                0L, 0L, 1L,
                1, null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, null, List.of(), List.of());

        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-perpetual instruments must not define funding settings");
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
                minValidSources, null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, null,
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
                0L, 1L, 0, 0L,
                0L, 0L, 1L,
                1, null, null, null, null, null, null, null,
                InstrumentStatus.TRADING, null, List.of(), List.of());
    }

    private InstrumentUpsertRequest deliveryRequest() {
        return expiringDerivativeRequest("BTC-USDT-260327", InstrumentType.DELIVERY,
                ContractType.LINEAR_DELIVERY, expiry(), expiry().plusSeconds(300),
                null, null, null, ContractSettlementMethod.CASH);
    }

    private InstrumentUpsertRequest optionRequest() {
        return expiringDerivativeRequest("BTC-USDT-260327-50000-C", InstrumentType.OPTION,
                ContractType.VANILLA_OPTION, expiry(), expiry().plusSeconds(300),
                "BTC-USDT", 50_000_000_000L, OptionType.CALL, ContractSettlementMethod.CASH);
    }

    private InstrumentUpsertRequest expiringDerivativeRequest(String symbol,
                                                              InstrumentType instrumentType,
                                                              ContractType contractType,
                                                              Instant expiryTime,
                                                              Instant deliveryTime,
                                                              String underlyingSymbol,
                                                              Long strikePriceUnits,
                                                              OptionType optionType,
                                                              ContractSettlementMethod settlementMethod) {
        return new InstrumentUpsertRequest(
                symbol, instrumentType, contractType, "BTC", "USDT", "USDT",
                1_000_000L, "USDT", 10_000_000L, 100_000L,
                1L, 100_000L, 500_000_000L, 1_000_000_000_000_000L, 10_000L,
                1, 3, List.of("LIMIT", "MARKET"), List.of("GTC", "IOC"),
                true, true, true, 100_000_000L, 10_000L,
                5_000L, 200L, 500L, 500_000_000_000_000L,
                300_000L, 25_000_000_000_000L, 0, 0L,
                0L, 0L, 1_000_000_000_000L,
                2, expiryTime, deliveryTime, underlyingSymbol, strikePriceUnits, optionType,
                optionType == null ? null : OptionExerciseStyle.EUROPEAN, settlementMethod,
                InstrumentStatus.PRE_TRADING, null,
                List.of(new RiskLimitBracket(1, 0L, 5_000_000_000_000L,
                        100_000_000L, 10_000L, 5_000L)),
                List.of(source("A", true), source("B", true)));
    }

    private Instant expiry() {
        return Instant.parse("2026-03-27T08:00:00Z");
    }

    private IndexSourceConfig source(String name, boolean enabled) {
        return new IndexSourceConfig(name, enabled, "https://example.com", "/ticker", "BTCUSDT",
                "BINANCE_BOOK_TICKER", "USDT", "USDT", null, null, null, "DISCOUNT",
                "MULTIPLY", 500_000L, true, "wss://example.com", "{}", "BINANCE_BOOK_TICKER",
                1_000_000L);
    }
}
