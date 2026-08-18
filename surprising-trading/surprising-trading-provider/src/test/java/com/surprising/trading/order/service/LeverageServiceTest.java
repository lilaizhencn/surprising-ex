package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.aeron.protocol.CoreLeverageView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.LeverageSettingRequest;
import com.surprising.trading.api.model.LeverageSettingResponse;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.order.model.InstrumentRule;
import com.surprising.trading.order.model.InstrumentRuleLookup;
import org.mockito.ArgumentCaptor;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LeverageServiceTest {

    @Test
    void setLeverageNormalizesSymbolAndPublishesFactWithoutDatabaseWrite() {
        InstrumentRuleLookup lookup = symbol -> Optional.of(rule(symbol));
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        LeverageService service = new LeverageService(lookup, aeron);

        LeverageSettingResponse response = service.set(new LeverageSettingRequest(1001L,
                ProductLine.LINEAR_PERPETUAL, "btc-usdt", MarginMode.ISOLATED, 10_000_000L,
                "user changed leverage"));

        assertThat(response.leveragePpm()).isEqualTo(10_000_000L);
        assertThat(response.source()).isEqualTo("USER");
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).command(org.mockito.ArgumentMatchers.eq(CoreMessageType.UPDATE_LEVERAGE),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1001L), payload.capture());
        assertThat(TradingCommandCodec.decodeUpdateLeverage(payload.getValue()))
                .isEqualTo(new com.surprising.aeron.protocol.UpdateLeverageCommand(
                        "BTC-USDT", CoreMarginMode.ISOLATED, 10_000_000L));
    }

    @Test
    void rejectsLeverageAboveInstrumentMaximum() {
        LeverageService service = new LeverageService(symbol -> Optional.of(rule(symbol)),
                mock(OrderAeronGateway.class));

        assertThatThrownBy(() -> service.set(new LeverageSettingRequest(1001L,
                ProductLine.LINEAR_PERPETUAL, "BTC-USDT", MarginMode.CROSS, 125_000_000L, "too high")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max leverage");
    }

    @Test
    void getFallsBackToInstrumentDefaultWhenUserSettingIsMissing() {
        InstrumentRuleLookup lookup = symbol -> Optional.of(rule(symbol));
        LeverageService service = new LeverageService(lookup, mock(OrderAeronGateway.class));

        assertThat(service.get(1001L, "BTC-USDT", null).source()).isEqualTo("INSTRUMENT_DEFAULT");
    }

    @Test
    void getDerivesProductLineFromInstrumentContractType() {
        InstrumentRuleLookup lookup = symbol -> Optional.of(rule(symbol, ContractType.INVERSE_DELIVERY));
        LeverageService service = new LeverageService(lookup, mock(OrderAeronGateway.class));

        assertThat(service.get(1001L, "btc-usd-260327", null).productLine())
                .isEqualTo(ProductLine.INVERSE_DELIVERY);
    }

    @Test
    void rejectsProductLineThatDoesNotMatchInstrumentContractType() {
        InstrumentRuleLookup lookup = symbol -> Optional.of(rule(symbol, ContractType.INVERSE_DELIVERY));
        LeverageService service = new LeverageService(lookup, mock(OrderAeronGateway.class));

        assertThatThrownBy(() -> service.set(new LeverageSettingRequest(1001L, ProductLine.LINEAR_PERPETUAL,
                "BTC-USD-260327", MarginMode.CROSS, 10_000_000L, "wrong line")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productLine");
    }

    @Test
    void getReturnsAeronAuthoritativeSetting() {
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        org.mockito.Mockito.when(aeron.leverage(1001L, "BTC-USDT", CoreMarginMode.CROSS))
                .thenReturn(new CoreLeverageView("BTC-USDT", CoreMarginMode.CROSS, 5_000_000L));
        LeverageService service = new LeverageService(symbol -> Optional.of(rule(symbol)), aeron);

        assertThat(service.get(1001L, "BTC-USDT", MarginMode.CROSS))
                .extracting(LeverageSettingResponse::leveragePpm, LeverageSettingResponse::source)
                .containsExactly(5_000_000L, "USER");
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
