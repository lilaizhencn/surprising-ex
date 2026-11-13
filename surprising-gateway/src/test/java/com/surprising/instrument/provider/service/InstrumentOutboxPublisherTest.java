package com.surprising.instrument.provider.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.ContractSettlementMethod;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.instrument.api.model.DeliverySettlementEvent;
import com.surprising.instrument.api.model.InstrumentStatus;
import com.surprising.instrument.provider.config.InstrumentProperties;
import com.surprising.instrument.provider.model.InstrumentOutboxRecord;
import com.surprising.instrument.provider.repository.InstrumentOutboxRepository;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class InstrumentOutboxPublisherTest {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().findAndAddModules().build();

    @Test
    void marksEventPublishedOnlyAfterSettlementDispatch() throws Exception {
        InstrumentProperties properties = new InstrumentProperties();
        InstrumentOutboxRepository repository = mock(InstrumentOutboxRepository.class);
        InstrumentOutboxPublisher.SettlementDispatcher dispatcher = mock(InstrumentOutboxPublisher.SettlementDispatcher.class);
        DeliverySettlementEvent event = new DeliverySettlementEvent(
                "1", 2L, ContractType.LINEAR_DELIVERY, 100_000L,
                Instant.now(), Instant.now(), ContractSettlementMethod.CASH, InstrumentStatus.CLOSED, Instant.now(), null);
        InstrumentOutboxRecord row = new InstrumentOutboxRecord(
                11L, "surprising.linear-delivery.delivery.settlements.v1", "1",
                "DELIVERY_SETTLEMENT", OBJECT_MAPPER.writeValueAsString(event), Instant.now());
        when(repository.claimPending(eq(100), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(row));

        new InstrumentOutboxPublisher(properties, repository, OBJECT_MAPPER, dispatcher).publishPending();

        verify(dispatcher).dispatch(ProductLine.LINEAR_DELIVERY, event);
        verify(repository).markPublished(eq(11L), any(Instant.class));
        verify(repository, never()).markFailed(eq(11L), any(), any(Instant.class));
    }

    @Test
    void retainsFailedEventForRetry() throws Exception {
        InstrumentProperties properties = new InstrumentProperties();
        InstrumentOutboxRepository repository = mock(InstrumentOutboxRepository.class);
        InstrumentOutboxPublisher.SettlementDispatcher dispatcher = mock(InstrumentOutboxPublisher.SettlementDispatcher.class);
        DeliverySettlementEvent event = new DeliverySettlementEvent(
                "1", 2L, ContractType.LINEAR_DELIVERY, 100_000L,
                Instant.now(), Instant.now(), ContractSettlementMethod.CASH, InstrumentStatus.CLOSED, Instant.now(), null);
        InstrumentOutboxRecord row = new InstrumentOutboxRecord(
                12L, "surprising.linear-delivery.delivery.settlements.v1", "1",
                "DELIVERY_SETTLEMENT", OBJECT_MAPPER.writeValueAsString(event), Instant.now());
        when(repository.claimPending(eq(100), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(row));
        doThrow(new IllegalStateException("core unavailable"))
                .when(dispatcher).dispatch(eq(ProductLine.LINEAR_DELIVERY), any());

        new InstrumentOutboxPublisher(properties, repository, OBJECT_MAPPER, dispatcher).publishPending();

        verify(repository, never()).markPublished(eq(12L), any(Instant.class));
        verify(repository).markFailed(eq(12L), any(), any(Instant.class));
    }
}
