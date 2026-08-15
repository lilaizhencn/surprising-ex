package com.surprising.account.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

        assertThat(service.fanout(event)).isEqualTo(1);

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(aeron).command(eq(CoreMessageType.SETTLE_INSTRUMENT), org.mockito.ArgumentMatchers.any(),
                eq(0L), payload.capture());
        assertThat(TradingCommandCodec.decodeSettleInstrument(payload.getValue())).isEqualTo(
                new SettleInstrumentCommand(SETTLEMENT_TIME.toEpochMilli(),
                        "BTC-USDT-260925-70000-C", 6, 0, 1_000));
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

    private static AccountProperties properties(ProductLine productLine) {
        AccountProperties properties = new AccountProperties();
        properties.getKafka().setProductLine(productLine);
        properties.getKafka().setProductTopicsEnabled(true);
        return properties;
    }
}
