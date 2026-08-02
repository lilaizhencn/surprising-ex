package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import com.surprising.trading.order.repository.LeverageSettingRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LeverageServiceTest {

    @Test
    void setLeverageNormalizesSymbolAndPersistsSetting() {
        LeverageSettingRepository repository = mock(LeverageSettingRepository.class);
        InstrumentRuleLookup lookup = symbol -> Optional.of(rule(symbol));
        OrderMarginSnapshotCache cache = new OrderMarginSnapshotCache();
        cache.markLeverageSnapshotReady(ProductLine.LINEAR_PERPETUAL);
        LeverageService service = new LeverageService(repository, lookup, cache);

        LeverageSettingResponse response = service.set(new LeverageSettingRequest(1001L, "btc-usdt",
                MarginMode.ISOLATED, 10_000_000L, "user changed leverage"));

        assertThat(response.leveragePpm()).isEqualTo(10_000_000L);
        assertThat(response.source()).isEqualTo("USER");
        assertThat(cache.lookupConfiguredLeverage(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT",
                MarginMode.ISOLATED)).contains(10_000_000L);
        verify(repository).upsert(eq(new LeverageSettingRequest(1001L, ProductLine.LINEAR_PERPETUAL,
                "BTC-USDT", MarginMode.ISOLATED, 10_000_000L, "user changed leverage")), any());
    }

    @Test
    void rejectsLeverageAboveInstrumentMaximum() {
        LeverageSettingRepository repository = mock(LeverageSettingRepository.class);
        OrderMarginSnapshotCache cache = new OrderMarginSnapshotCache();
        cache.markLeverageSnapshotReady(ProductLine.LINEAR_PERPETUAL);
        LeverageService service = new LeverageService(repository, symbol -> Optional.of(rule(symbol)), cache);

        assertThatThrownBy(() -> service.set(new LeverageSettingRequest(1001L, "BTC-USDT",
                MarginMode.CROSS, 125_000_000L, "too high")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max leverage");
    }

    @Test
    void getFallsBackToInstrumentDefaultWhenUserSettingIsMissing() {
        LeverageSettingRepository repository = mock(LeverageSettingRepository.class);
        InstrumentRuleLookup lookup = symbol -> Optional.of(rule(symbol));
        OrderMarginSnapshotCache cache = new OrderMarginSnapshotCache();
        cache.markLeverageSnapshotReady(ProductLine.LINEAR_PERPETUAL);
        LeverageService service = new LeverageService(repository, lookup, cache);

        assertThat(service.get(1001L, "BTC-USDT", null).source()).isEqualTo("INSTRUMENT_DEFAULT");
    }

    @Test
    void getDerivesProductLineFromInstrumentContractType() {
        LeverageSettingRepository repository = mock(LeverageSettingRepository.class);
        InstrumentRuleLookup lookup = symbol -> Optional.of(rule(symbol, ContractType.INVERSE_DELIVERY));
        OrderMarginSnapshotCache cache = new OrderMarginSnapshotCache();
        cache.markLeverageSnapshotReady(ProductLine.INVERSE_DELIVERY);
        LeverageService service = new LeverageService(repository, lookup, cache);

        assertThat(service.get(1001L, "btc-usd-260327", null).productLine())
                .isEqualTo(ProductLine.INVERSE_DELIVERY);
    }

    @Test
    void rejectsProductLineThatDoesNotMatchInstrumentContractType() {
        LeverageSettingRepository repository = mock(LeverageSettingRepository.class);
        InstrumentRuleLookup lookup = symbol -> Optional.of(rule(symbol, ContractType.INVERSE_DELIVERY));
        LeverageService service = new LeverageService(repository, lookup, new OrderMarginSnapshotCache());

        assertThatThrownBy(() -> service.set(new LeverageSettingRequest(1001L, ProductLine.LINEAR_PERPETUAL,
                "BTC-USD-260327", MarginMode.CROSS, 10_000_000L, "wrong line")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productLine");
    }

    private InstrumentRule rule(String symbol) {
        return rule(symbol, ContractType.LINEAR_PERPETUAL);
    }

    private InstrumentRule rule(String symbol, ContractType contractType) {
        return new InstrumentRule(symbol, 1L, "TRADING", contractType,
                Set.of("LIMIT", "MARKET"), Set.of("GTC", "IOC"), true, true, true,
                1L, 100_000L, 1L, 1_000_000_000L, 10_000L, 100_000_000L, 10_000L);
    }

}
