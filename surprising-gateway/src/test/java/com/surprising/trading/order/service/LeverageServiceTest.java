package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

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
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LeverageServiceTest {

    @Test
    void explicitCrossMarginRepricingIsEncodedInTheJournalCommand() {
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        LeverageService service = new LeverageService(id -> Optional.of(rule(id)), aeron);
        service.set(new LeverageSettingRequest(1001, ProductLine.LINEAR_PERPETUAL, "1", MarginMode.CROSS,
                5_000_000, "fund maker margin", true));
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).command(org.mockito.ArgumentMatchers.eq(CoreMessageType.UPDATE_LEVERAGE),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1001L), payload.capture());
        assertThat(TradingCommandCodec.decodeUpdateLeverage(payload.getValue()).repriceCrossMargin()).isTrue();
    }

    @Test
    void setLeverageNormalizesSymbolAndPublishesFactWithoutDatabaseWrite() {
        InstrumentRuleLookup lookup = instrumentId -> Optional.of(rule(instrumentId));
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        LeverageService service = new LeverageService(lookup, aeron);

        LeverageSettingResponse response = service.set(new LeverageSettingRequest(1001L,
                ProductLine.LINEAR_PERPETUAL, "1", MarginMode.ISOLATED, 10_000_000L,
                "user changed leverage"));

        assertThat(response.leveragePpm()).isEqualTo(10_000_000L);
        assertThat(response.source()).isEqualTo("USER");
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).command(org.mockito.ArgumentMatchers.eq(CoreMessageType.UPDATE_LEVERAGE),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1001L), payload.capture());
        assertThat(TradingCommandCodec.decodeUpdateLeverage(payload.getValue()))
                .isEqualTo(new com.surprising.aeron.protocol.UpdateLeverageCommand(
                        "1", CoreMarginMode.ISOLATED, 10_000_000L));
    }

    @Test
    void rejectsLeverageAboveInstrumentMaximum() {
        LeverageService service = new LeverageService(instrumentId -> Optional.of(rule(instrumentId)),
                mock(OrderAeronGateway.class));

        assertThatThrownBy(() -> service.set(new LeverageSettingRequest(1001L,
                ProductLine.LINEAR_PERPETUAL, "1", MarginMode.CROSS, 125_000_000L, "too high")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max leverage");
    }

    @Test
    void retryAfterRejectedAttemptUsesNewCommandId() {
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        LeverageService service = new LeverageService(instrumentId -> Optional.of(rule(instrumentId)), aeron);
        LeverageSettingRequest request = new LeverageSettingRequest(1001L,
                ProductLine.LINEAR_PERPETUAL, "1", MarginMode.CROSS, 10_000_000L,
                "retry after canceling orders");
        org.mockito.Mockito.doThrow(new IllegalStateException("open orders exist")).doReturn(null)
                .when(aeron).command(org.mockito.ArgumentMatchers.eq(CoreMessageType.UPDATE_LEVERAGE),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1001L),
                        org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> service.set(request)).hasMessageContaining("open orders");
        service.set(request);

        ArgumentCaptor<UUID> commandIds = ArgumentCaptor.forClass(UUID.class);
        verify(aeron, times(2)).command(org.mockito.ArgumentMatchers.eq(CoreMessageType.UPDATE_LEVERAGE),
                commandIds.capture(), org.mockito.ArgumentMatchers.eq(1001L),
                org.mockito.ArgumentMatchers.any());
        assertThat(commandIds.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void getFallsBackToInstrumentDefaultWhenUserSettingIsMissing() {
        InstrumentRuleLookup lookup = instrumentId -> Optional.of(rule(instrumentId));
        LeverageService service = new LeverageService(lookup, mock(OrderAeronGateway.class));

        assertThat(service.get(1001L, "1", null).source()).isEqualTo("INSTRUMENT_DEFAULT");
    }

    @Test
    void getDerivesProductLineFromInstrumentContractType() {
        InstrumentRuleLookup lookup = instrumentId -> Optional.of(rule(instrumentId, ContractType.INVERSE_DELIVERY));
        LeverageService service = new LeverageService(lookup, mock(OrderAeronGateway.class));

        assertThat(service.get(1001L, "54", null).productLine())
                .isEqualTo(ProductLine.INVERSE_DELIVERY);
    }

    @Test
    void rejectsProductLineThatDoesNotMatchInstrumentContractType() {
        InstrumentRuleLookup lookup = instrumentId -> Optional.of(rule(instrumentId, ContractType.INVERSE_DELIVERY));
        LeverageService service = new LeverageService(lookup, mock(OrderAeronGateway.class));

        assertThatThrownBy(() -> service.set(new LeverageSettingRequest(1001L, ProductLine.LINEAR_PERPETUAL,
                "54", MarginMode.CROSS, 10_000_000L, "wrong line")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productLine");
    }

    @Test
    void getReturnsAeronAuthoritativeSetting() {
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        org.mockito.Mockito.when(aeron.leverage(1001L, "1", CoreMarginMode.CROSS))
                .thenReturn(new CoreLeverageView("1", CoreMarginMode.CROSS, 5_000_000L));
        LeverageService service = new LeverageService(instrumentId -> Optional.of(rule(instrumentId)), aeron);

        assertThat(service.get(1001L, "1", MarginMode.CROSS))
                .extracting(LeverageSettingResponse::leveragePpm, LeverageSettingResponse::source)
                .containsExactly(5_000_000L, "USER");
    }

    private InstrumentRule rule(String instrumentId) {
        return rule(instrumentId, ContractType.LINEAR_PERPETUAL);
    }

    private InstrumentRule rule(String instrumentId, ContractType contractType) {
        return new InstrumentRule(instrumentId, 1L, "TRADING", contractType,
                Set.of("LIMIT", "MARKET"), Set.of("GTC", "IOC"), true, true, true,
                1L, 100_000L, 1L, 1_000_000_000L, 10_000L, 100_000_000L, 10_000L);
    }

}
