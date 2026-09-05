package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreSettlementProgressCodec;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.instrument.api.model.ContractSettlementMethod;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.api.model.OptionExerciseEvent;
import com.surprising.instrument.api.model.OptionExerciseStyle;
import com.surprising.instrument.api.model.OptionType;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ExpiringContractSettlementFanoutServiceTest {

    private static final Instant SETTLEMENT_TIME = Instant.parse("2026-07-01T08:00:00Z");

    @Test
    void submitsOneDeliverySettlementCommand() {
        AccountAeronGateway aeron = mock(AccountAeronGateway.class);
        AccountProperties properties = properties(ProductLine.LINEAR_DELIVERY);
        var service = new ExpiringContractSettlementFanoutService(aeron, properties);
        var event = new DeliverySettlementEvent("BTC-USDT-260327", 4, ContractType.LINEAR_DELIVERY,
                100, SETTLEMENT_TIME, SETTLEMENT_TIME, ContractSettlementMethod.CASH,
                InstrumentStatus.CLOSED, SETTLEMENT_TIME, null);
        stubCompleted(aeron);

        assertThat(service.fanout(event)).isEqualTo(1);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).command(eq(CoreMessageType.SETTLE_INSTRUMENT), org.mockito.ArgumentMatchers.any(),
                eq(0L), payload.capture());
        assertThat(TradingCommandCodec.decodeSettleInstrument(payload.getValue())).isEqualTo(
                new SettleInstrumentCommand(SETTLEMENT_TIME.toEpochMilli(), "BTC-USDT-260327", 4, 100, 0));
    }

    @Test
    void submitsOneOptionExerciseCommand() {
        AccountAeronGateway aeron = mock(AccountAeronGateway.class);
        AccountProperties properties = properties(ProductLine.OPTION);
        var service = new ExpiringContractSettlementFanoutService(aeron, properties);
        var event = new OptionExerciseEvent("BTC-USDT-260925-70000-C", 6, "BTC-USDT", 70_000_000,
                71_000_000, 1_000, OptionType.CALL, OptionExerciseStyle.EUROPEAN,
                SETTLEMENT_TIME, SETTLEMENT_TIME, ContractSettlementMethod.CASH,
                InstrumentStatus.CLOSED, SETTLEMENT_TIME, null);
        stubCompleted(aeron);

        assertThat(service.fanout(event)).isEqualTo(1);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).command(eq(CoreMessageType.SETTLE_INSTRUMENT), org.mockito.ArgumentMatchers.any(),
                eq(0L), payload.capture());
        assertThat(TradingCommandCodec.decodeSettleInstrument(payload.getValue())).isEqualTo(
                new SettleInstrumentCommand(SETTLEMENT_TIME.toEpochMilli(),
                        "BTC-USDT-260925-70000-C", 6, 71_000_000, 1_000));
    }

    @Test
    void resumesSettlementFromCoreCursor() {
        AccountAeronGateway aeron = mock(AccountAeronGateway.class);
        AccountProperties properties = properties(ProductLine.LINEAR_DELIVERY);
        var service = new ExpiringContractSettlementFanoutService(aeron, properties);
        var event = new DeliverySettlementEvent("BTC-USDT-260327", 4, ContractType.LINEAR_DELIVERY,
                100, SETTLEMENT_TIME, SETTLEMENT_TIME, ContractSettlementMethod.CASH,
                InstrumentStatus.CLOSED, SETTLEMENT_TIME, null);
        long settlementId = SETTLEMENT_TIME.toEpochMilli();
        when(aeron.query(eq(CoreMessageType.SETTLEMENT_PROGRESS_QUERY), any(), any()))
                .thenReturn(new CoreResponse(ResponseStatus.OK, 0, 0,
                        CoreSettlementProgressCodec.encode(new CoreSettlementProgressView(
                                settlementId, false, 42, 0))));
        when(aeron.command(eq(CoreMessageType.SETTLE_INSTRUMENT), any(), eq(0L), any()))
                .thenReturn(new CoreResponse(ResponseStatus.APPLIED, 1, 0,
                        CoreSettlementProgressCodec.encode(new CoreSettlementProgressView(
                                settlementId, true, 0, 1))));

        service.fanout(event);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).command(eq(CoreMessageType.SETTLE_INSTRUMENT), any(), eq(0L), payload.capture());
        assertThat(TradingCommandCodec.decodeSettleInstrument(payload.getValue()).cursorUserId()).isEqualTo(42);
    }

    @Test
    void acceptsDescendingOrderPages() {
        var aeron = mock(AccountAeronGateway.class);
        stubCompleted(aeron);
        when(aeron.command(eq(CoreMessageType.SETTLE_INSTRUMENT), any(), eq(0L), any()))
                .thenReturn(response(new CoreSettlementProgressView(id(), false, false, 100, 0, 16, 0)))
                .thenReturn(response(new CoreSettlementProgressView(id(), false, false, 50, 0, 16, 0)))
                .thenReturn(response(new CoreSettlementProgressView(id(), true, 0, 1)));
        new ExpiringContractSettlementFanoutService(aeron, properties(ProductLine.LINEAR_DELIVERY)).fanout(event());
        var payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron, times(3)).command(eq(CoreMessageType.SETTLE_INSTRUMENT), any(), eq(0L), payload.capture());
        assertThat(payload.getAllValues().stream().map(TradingCommandCodec::decodeSettleInstrument)
                .map(SettleInstrumentCommand::cursorOrderId)).containsExactly(0L, 100L, 50L);
    }

    @Test
    void retriesBlockedPageWithFreshTransportIdentity() {
        var aeron = mock(AccountAeronGateway.class);
        stubCompleted(aeron);
        var blocked = new CoreSettlementProgressView(id(), false, true, 0, 42, 0, 0, 20);
        when(aeron.query(eq(CoreMessageType.SETTLEMENT_PROGRESS_QUERY), any(), any()))
                .thenReturn(response(blocked));
        when(aeron.command(eq(CoreMessageType.SETTLE_INSTRUMENT), any(), eq(0L), any()))
                .thenReturn(response(blocked))
                .thenReturn(response(new CoreSettlementProgressView(id(), true, 0, 1)));
        var service = new ExpiringContractSettlementFanoutService(aeron, properties(ProductLine.LINEAR_DELIVERY));
        assertThatThrownBy(() -> service.fanout(event())).hasMessageContaining("awaits insurance");
        assertThat(service.fanout(event())).isEqualTo(1);
        var ids = ArgumentCaptor.forClass(UUID.class);
        var payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron, times(2)).command(eq(CoreMessageType.SETTLE_INSTRUMENT), ids.capture(), eq(0L), payload.capture());
        assertThat(ids.getAllValues().get(0)).isNotEqualTo(ids.getAllValues().get(1));
        assertThat(payload.getAllValues().stream().map(TradingCommandCodec::decodeSettleInstrument)
                .map(SettleInstrumentCommand::cursorUserId)).containsExactly(42L, 42L);
    }

    @Test
    void queryFailureMustNotAcknowledgeLifecycleEvent() {
        var aeron = mock(AccountAeronGateway.class);
        when(aeron.query(eq(CoreMessageType.SETTLEMENT_PROGRESS_QUERY), any(), any()))
                .thenThrow(new IllegalStateException("offline"));
        var service = new ExpiringContractSettlementFanoutService(aeron, properties(ProductLine.LINEAR_DELIVERY));
        assertThatThrownBy(() -> service.fanout(event())).hasMessage("offline");
        verify(aeron, never()).command(any(), any(), eq(0L), any());
    }

    @Test
    void rejectedCommandMustNotBeReplacedByAnEmptyProgressQuery() {
        var aeron = mock(AccountAeronGateway.class);
        stubCompleted(aeron);
        when(aeron.command(eq(CoreMessageType.SETTLE_INSTRUMENT), any(), eq(0L), any()))
                .thenReturn(new CoreResponse(ResponseStatus.REJECTED, 0, 0));
        var service = new ExpiringContractSettlementFanoutService(aeron, properties(ProductLine.LINEAR_DELIVERY));
        assertThatThrownBy(() -> service.fanout(event())).hasMessageContaining("command rejected");
    }

    private static long id() { return SETTLEMENT_TIME.toEpochMilli(); }

    private static DeliverySettlementEvent event() {
        return new DeliverySettlementEvent("BTC-USDT-260327", 4, ContractType.LINEAR_DELIVERY,
                100, SETTLEMENT_TIME, SETTLEMENT_TIME, ContractSettlementMethod.CASH,
                InstrumentStatus.CLOSED, SETTLEMENT_TIME, null);
    }

    private static CoreResponse response(CoreSettlementProgressView progress) {
        return new CoreResponse(ResponseStatus.APPLIED, 1, 0, CoreSettlementProgressCodec.encode(progress));
    }

    private static void stubCompleted(AccountAeronGateway aeron) {
        when(aeron.query(eq(CoreMessageType.SETTLEMENT_PROGRESS_QUERY), any(), any()))
                .thenReturn(new CoreResponse(ResponseStatus.OK, 0, 0,
                        CoreSettlementProgressCodec.encode(new CoreSettlementProgressView(0, true, 0, 0))));
        when(aeron.command(eq(CoreMessageType.SETTLE_INSTRUMENT), any(), eq(0L), any()))
                .thenReturn(response(new CoreSettlementProgressView(id(), true, 0, 0)));
    }

    private static AccountProperties properties(ProductLine productLine) {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(productLine);
        return properties;
    }
}
