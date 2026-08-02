package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LeverageServiceTest {

    @Test
    void setLeverageNormalizesSymbolAndPublishesFactWithoutDatabaseWrite() {
        InstrumentRuleLookup lookup = symbol -> Optional.of(rule(symbol));
        OrderMarginSnapshotCache cache = new OrderMarginSnapshotCache();
        cache.markLeverageSnapshotReady(ProductLine.LINEAR_PERPETUAL);
        LeverageSettingEventPublisher publisher = mock(LeverageSettingEventPublisher.class);
        OrderIdSequenceStore sequence = mock(OrderIdSequenceStore.class);
        when(sequence.next()).thenReturn(701L);
        LeverageService service = new LeverageService(lookup, cache, publisher, sequence);

        LeverageSettingResponse response = service.set(new LeverageSettingRequest(1001L,
                ProductLine.LINEAR_PERPETUAL, "btc-usdt", MarginMode.ISOLATED, 10_000_000L,
                "user changed leverage"));

        assertThat(response.leveragePpm()).isEqualTo(10_000_000L);
        assertThat(response.source()).isEqualTo("USER");
        org.mockito.Mockito.verify(publisher).publish(
                org.mockito.ArgumentMatchers.eq(new LeverageSettingRequest(1001L, ProductLine.LINEAR_PERPETUAL,
                        "BTC-USDT", MarginMode.ISOLATED, 10_000_000L, "user changed leverage")),
                org.mockito.ArgumentMatchers.eq(701L), org.mockito.ArgumentMatchers.any(Instant.class));
    }

    @Test
    void rejectsLeverageAboveInstrumentMaximum() {
        OrderMarginSnapshotCache cache = new OrderMarginSnapshotCache();
        cache.markLeverageSnapshotReady(ProductLine.LINEAR_PERPETUAL);
        LeverageService service = new LeverageService(symbol -> Optional.of(rule(symbol)), cache,
                mock(LeverageSettingEventPublisher.class), mock(OrderIdSequenceStore.class));

        assertThatThrownBy(() -> service.set(new LeverageSettingRequest(1001L,
                ProductLine.LINEAR_PERPETUAL, "BTC-USDT", MarginMode.CROSS, 125_000_000L, "too high")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max leverage");
    }

    @Test
    void getFallsBackToInstrumentDefaultWhenUserSettingIsMissing() {
        InstrumentRuleLookup lookup = symbol -> Optional.of(rule(symbol));
        OrderMarginSnapshotCache cache = new OrderMarginSnapshotCache();
        cache.markLeverageSnapshotReady(ProductLine.LINEAR_PERPETUAL);
        LeverageService service = new LeverageService(lookup, cache, mock(LeverageSettingEventPublisher.class),
                mock(OrderIdSequenceStore.class));

        assertThat(service.get(1001L, "BTC-USDT", null).source()).isEqualTo("INSTRUMENT_DEFAULT");
    }

    @Test
    void getDerivesProductLineFromInstrumentContractType() {
        InstrumentRuleLookup lookup = symbol -> Optional.of(rule(symbol, ContractType.INVERSE_DELIVERY));
        OrderMarginSnapshotCache cache = new OrderMarginSnapshotCache();
        cache.markLeverageSnapshotReady(ProductLine.INVERSE_DELIVERY);
        LeverageService service = new LeverageService(lookup, cache, mock(LeverageSettingEventPublisher.class),
                mock(OrderIdSequenceStore.class));

        assertThat(service.get(1001L, "btc-usd-260327", null).productLine())
                .isEqualTo(ProductLine.INVERSE_DELIVERY);
    }

    @Test
    void rejectsProductLineThatDoesNotMatchInstrumentContractType() {
        InstrumentRuleLookup lookup = symbol -> Optional.of(rule(symbol, ContractType.INVERSE_DELIVERY));
        LeverageService service = new LeverageService(lookup, new OrderMarginSnapshotCache(),
                mock(LeverageSettingEventPublisher.class), mock(OrderIdSequenceStore.class));

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
