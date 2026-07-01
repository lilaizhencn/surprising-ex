package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import com.surprising.trading.order.repository.LeverageSettingRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LeverageServiceTest {

    @Test
    void setLeverageNormalizesSymbolAndPersistsSetting() {
        LeverageSettingRepository repository = mock(LeverageSettingRepository.class);
        InstrumentRuleLookup lookup = symbol -> Optional.of(rule(symbol));
        LeverageService service = new LeverageService(repository, lookup);
        LeverageSettingResponse persisted = response("BTC-USDT", MarginMode.ISOLATED, 10_000_000L, "USER");
        when(repository.userSetting(eq(1001L), eq("BTC-USDT"), eq(MarginMode.ISOLATED), eq(100_000_000L)))
                .thenReturn(Optional.of(persisted));

        LeverageSettingResponse response = service.set(new LeverageSettingRequest(1001L, "btc-usdt",
                MarginMode.ISOLATED, 10_000_000L, "user changed leverage"));

        assertThat(response).isEqualTo(persisted);
        verify(repository).upsert(eq(new LeverageSettingRequest(1001L, "BTC-USDT", MarginMode.ISOLATED,
                10_000_000L, "user changed leverage")), any());
    }

    @Test
    void rejectsLeverageAboveInstrumentMaximum() {
        LeverageSettingRepository repository = mock(LeverageSettingRepository.class);
        LeverageService service = new LeverageService(repository, symbol -> Optional.of(rule(symbol)));

        assertThatThrownBy(() -> service.set(new LeverageSettingRequest(1001L, "BTC-USDT",
                MarginMode.CROSS, 125_000_000L, "too high")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max leverage");
    }

    @Test
    void getFallsBackToInstrumentDefaultWhenUserSettingIsMissing() {
        LeverageSettingRepository repository = mock(LeverageSettingRepository.class);
        InstrumentRuleLookup lookup = symbol -> Optional.of(rule(symbol));
        LeverageService service = new LeverageService(repository, lookup);
        LeverageSettingResponse fallback = response("BTC-USDT", MarginMode.CROSS,
                100_000_000L, "INSTRUMENT_DEFAULT");
        when(repository.userSetting(eq(1001L), eq("BTC-USDT"), eq(MarginMode.CROSS), eq(100_000_000L)))
                .thenReturn(Optional.empty());
        when(repository.instrumentDefault(eq(1001L), eq("BTC-USDT"), eq(MarginMode.CROSS),
                eq(100_000_000L), eq(10_000L))).thenReturn(fallback);

        assertThat(service.get(1001L, "BTC-USDT", null)).isEqualTo(fallback);
    }

    private InstrumentRule rule(String symbol) {
        return new InstrumentRule(symbol, 1L, "TRADING", ContractType.LINEAR_PERPETUAL,
                Set.of("LIMIT", "MARKET"), Set.of("GTC", "IOC"), true, true, true,
                1L, 100_000L, 1L, 1_000_000_000L, 10_000L, 100_000_000L, 10_000L);
    }

    private LeverageSettingResponse response(String symbol, MarginMode marginMode, long leveragePpm, String source) {
        return new LeverageSettingResponse(1001L, symbol, marginMode, leveragePpm,
                100_000_000L, 10_000L, source, Instant.parse("2026-07-01T00:00:00Z"));
    }
}
