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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LeverageServiceTest {
    private final com.surprising.realtime.api.ValkeyUserQueries queries = mock(com.surprising.realtime.api.ValkeyUserQueries.class);

    @org.junit.jupiter.api.BeforeEach
    void projectedAccount() {
        org.mockito.Mockito.when(queries.require(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.isNull()))
                .thenAnswer(call -> view(call.getArgument(0), call.getArgument(1), java.util.List.of()));
    }

    private com.surprising.realtime.api.UserReadView view(ProductLine line, long user, java.util.List<CoreLeverageView> leverages) {
        var account = new com.surprising.aeron.protocol.CoreUserStateView(line, user, 1,
                com.surprising.aeron.protocol.CorePositionMode.ONE_WAY, java.util.List.of(), java.util.List.of(), java.util.List.of(), leverages);
        return new com.surprising.realtime.api.UserReadView("READY", "1", 1, account, java.util.List.of(), java.util.List.of(), 1, java.util.List.of());
    }

    @Test
    void allProductsReadOnlyTheirRealtimeLeverageAndRejectUnavailableOrWrongAccounts() {
        for (var type : ContractType.values()) {
            var line = type.productLine();
            var aeron = mock(OrderAeronGateway.class);
            var service = new LeverageService(id -> Optional.of(rule(id, type)), aeron, queries);
            org.mockito.Mockito.when(queries.require(line, 1001L, null))
                    .thenReturn(view(line, 1001L, java.util.List.of(new CoreLeverageView("73", CoreMarginMode.CROSS, 5_000_000L))));
            assertThat(service.get(1001L, "73", MarginMode.CROSS, line).leveragePpm()).isEqualTo(5_000_000L);
            org.mockito.Mockito.when(queries.require(line, 1001L, null)).thenReturn(view(line, 1002L, java.util.List.of()));
            assertThatThrownBy(() -> service.get(1001L, "73", MarginMode.CROSS, line)).hasMessageContaining("READ_VIEW_");
            org.mockito.Mockito.when(queries.require(line, 1001L, null)).thenThrow(new com.surprising.realtime.api.exception.ReadViewUnavailableException("READ_VIEW_STALE"));
            assertThatThrownBy(() -> service.get(1001L, "73", MarginMode.CROSS, line)).hasMessageContaining("READ_VIEW_STALE");
            org.mockito.Mockito.verifyNoInteractions(aeron);
            org.mockito.Mockito.reset(queries);
        }
    }

    @Test
    void explicitCrossMarginRepricingIsEncodedInTheJournalCommand() {
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        LeverageService service = new LeverageService(id -> Optional.of(rule(id)), aeron, queries);
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
        LeverageService service = new LeverageService(lookup, aeron, queries);

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
                mock(OrderAeronGateway.class), queries);

        assertThatThrownBy(() -> service.set(new LeverageSettingRequest(1001L,
                ProductLine.LINEAR_PERPETUAL, "1", MarginMode.CROSS, 125_000_000L, "too high")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max leverage");
    }

    @Test
    void retryAfterRejectedAttemptUsesNewCommandId() {
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        LeverageService service = new LeverageService(instrumentId -> Optional.of(rule(instrumentId)), aeron, queries);
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
        LeverageService service = new LeverageService(lookup, mock(OrderAeronGateway.class), queries);

        assertThat(service.get(1001L, "1", null).source()).isEqualTo("INSTRUMENT_DEFAULT");
    }

    @Test
    void getDerivesProductLineFromInstrumentContractType() {
        InstrumentRuleLookup lookup = instrumentId -> Optional.of(rule(instrumentId, ContractType.INVERSE_DELIVERY));
        LeverageService service = new LeverageService(lookup, mock(OrderAeronGateway.class), queries);

        assertThat(service.get(1001L, "54", null).productLine())
                .isEqualTo(ProductLine.INVERSE_DELIVERY);
    }

    @Test
    void rejectsProductLineThatDoesNotMatchInstrumentContractType() {
        InstrumentRuleLookup lookup = instrumentId -> Optional.of(rule(instrumentId, ContractType.INVERSE_DELIVERY));
        LeverageService service = new LeverageService(lookup, mock(OrderAeronGateway.class), queries);

        assertThatThrownBy(() -> service.set(new LeverageSettingRequest(1001L, ProductLine.LINEAR_PERPETUAL,
                "54", MarginMode.CROSS, 10_000_000L, "wrong line")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("productLine");
    }

    @Test
    void getReturnsRealtimeSettingWithoutCoreQuery() {
        OrderAeronGateway aeron = mock(OrderAeronGateway.class);
        org.mockito.Mockito.when(queries.require(ProductLine.LINEAR_PERPETUAL, 1001L, null))
                .thenReturn(view(ProductLine.LINEAR_PERPETUAL, 1001L, java.util.List.of(new CoreLeverageView("1", CoreMarginMode.CROSS, 5_000_000L))));
        LeverageService service = new LeverageService(instrumentId -> Optional.of(rule(instrumentId)), aeron, queries);

        assertThat(service.get(1001L, "1", MarginMode.CROSS))
                .extracting(LeverageSettingResponse::leveragePpm, LeverageSettingResponse::source)
                .containsExactly(5_000_000L, "USER");
    }

    @ParameterizedTest
    @EnumSource(value=ContractType.class, names={"LINEAR_DELIVERY","INVERSE_DELIVERY","VANILLA_OPTION"})
    void settlingLinearDeliveryReadsProjectedLeverageButStillRejectsUpdates(ContractType type) {
        var aeron = mock(OrderAeronGateway.class);
        org.mockito.Mockito.when(queries.require(type.productLine(), 1001L, null))
                .thenReturn(view(type.productLine(), 1001L, java.util.List.of(new CoreLeverageView("73", CoreMarginMode.CROSS, 5_000_000L))));
        var service = new LeverageService(id -> Optional.of(rule(id, type, "SETTLING")), aeron, queries);
        assertThat(service.get(1001L, "73", MarginMode.CROSS, type.productLine()).leveragePpm()).isEqualTo(5_000_000L);
        assertThatThrownBy(() -> service.set(new LeverageSettingRequest(1001L, type.productLine(),
                "73", MarginMode.CROSS, 10_000_000L, "settling"))).hasMessageContaining("not trading");
        verify(queries).require(type.productLine(), 1001L, null);
        org.mockito.Mockito.verifyNoInteractions(aeron);
    }

    @ParameterizedTest
    @EnumSource(value=ContractType.class, names={"LINEAR_DELIVERY","INVERSE_DELIVERY","VANILLA_OPTION"})
    void closedDeliveryLeverageRemainsReadOnlyAndUsesRealtime(ContractType type) {
        var aeron = mock(OrderAeronGateway.class);
        org.mockito.Mockito.when(queries.require(type.productLine(), 1001L, null))
                .thenReturn(view(type.productLine(), 1001L, java.util.List.of()));
        var service = new LeverageService(id -> Optional.of(rule(id, type, "CLOSED")), aeron, queries);
        assertThat(service.get(1001L, "73", MarginMode.CROSS, type.productLine()).source()).isEqualTo("INSTRUMENT_DEFAULT");
        assertThatThrownBy(() -> service.set(new LeverageSettingRequest(1001L, type.productLine(),
                "73", MarginMode.CROSS, 10_000_000L, "closed"))).hasMessageContaining("not trading");
        org.mockito.Mockito.verifyNoInteractions(aeron);
    }

    @Test
    void settlingLeverageReadCannotCrossProductBoundary() {
        var aeron = mock(OrderAeronGateway.class);
        var service = new LeverageService(id -> Optional.of(rule(id, ContractType.LINEAR_DELIVERY, "SETTLING")), aeron, queries);
        assertThatThrownBy(() -> service.get(1001L, "73", MarginMode.CROSS, ProductLine.INVERSE_DELIVERY))
                .hasMessageContaining("productLine");
        org.mockito.Mockito.verifyNoInteractions(aeron);
    }

    @Test
    void otherUnlistedLeverageReadsRetainTheirExistingRestrictions() {
        for (var type : ContractType.values()) for (var status : java.util.List.of("DRAFT", "PRE_TRADING", "HALT", "SETTLING", "CLOSED")) {
            if (type.productLine().isDeliveryProduct() && (status.equals("SETTLING") || status.equals("CLOSED"))) continue;
            var aeron = mock(OrderAeronGateway.class);
            var service = new LeverageService(id -> Optional.of(rule(id, type, status)), aeron, queries);
            assertThatThrownBy(() -> service.get(1001L, "73", MarginMode.CROSS, type.productLine()))
                    .hasMessageContaining("not trading");
            org.mockito.Mockito.verifyNoInteractions(aeron);
        }
    }

    private InstrumentRule rule(String instrumentId) {
        return rule(instrumentId, ContractType.LINEAR_PERPETUAL);
    }

    private InstrumentRule rule(String instrumentId, ContractType contractType) {
        return rule(instrumentId, contractType, "TRADING");
    }

    private InstrumentRule rule(String instrumentId, ContractType contractType, String status) {
        return new InstrumentRule(instrumentId, 1L, status, contractType,
                Set.of("LIMIT", "MARKET"), Set.of("GTC", "IOC"), true, true, true,
                1L, 100_000L, 1L, 1_000_000_000L, 10_000L, 100_000_000L, 10_000L);
    }

}
