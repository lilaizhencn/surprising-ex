package com.surprising.trading.trigger.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.TriggerOrderResponse;
import com.surprising.trading.api.model.TriggerOrderStatus;
import com.surprising.trading.api.model.TriggerOrderUpdatedEvent;
import com.surprising.trading.trigger.config.TriggerProperties;
import com.surprising.trading.trigger.model.TriggerOrderRecord;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

class TriggerOrderOutboxRepositoryTest {

    @Test
    void enqueuePersistsFullStatusSnapshotOnTheProductTopic() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerOrderRepository triggerRepository = mock(TriggerOrderRepository.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        TriggerProperties properties = new TriggerProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        TriggerOrderOutboxRepository repository = new TriggerOrderOutboxRepository(
                jdbcTemplate, triggerRepository, properties, objectMapper);
        TriggerOrderRecord order = mock(TriggerOrderRecord.class);
        TriggerOrderResponse response = mock(TriggerOrderResponse.class);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(order.productLine()).thenReturn(ProductLine.LINEAR_DELIVERY);
        when(order.triggerOrderId()).thenReturn(501L);
        when(order.symbol()).thenReturn("BTC-USDT-260925");
        when(order.status()).thenReturn(TriggerOrderStatus.TRIGGERED);
        when(order.traceId()).thenReturn("trace-trigger");
        when(order.updatedAt()).thenReturn(now);
        when(triggerRepository.nextSequence("event")).thenReturn(701L);
        when(triggerRepository.nextSequence("outbox")).thenReturn(801L);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"eventId\":701}");
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        TriggerOrderUpdatedEvent event = repository.enqueue(order, response);

        assertThat(event.eventId()).isEqualTo(701L);
        assertThat(event.productLine()).isEqualTo(ProductLine.LINEAR_DELIVERY);
        assertThat(event.order()).isSameAs(response);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertThat(args.getValue()).containsExactly(
                801L, "TRIGGER_ORDER", 501L,
                "surprising.linear-delivery.trigger-order.events.v1", "BTC-USDT-260925", "TRIGGERED",
                "{\"eventId\":701}", Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    @Test
    void claimPendingScopesSharedOutboxToTriggerAggregateAndTopic() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        TriggerProperties properties = new TriggerProperties();
        TriggerOrderOutboxRepository repository = new TriggerOrderOutboxRepository(
                jdbcTemplate, mock(TriggerOrderRepository.class), properties, mock(ObjectMapper.class));
        when(jdbcTemplate.query(anyString(), anyRowMapper(), eq("TRIGGER_ORDER"),
                eq("surprising.perp.trigger-order.events.v1"), eq("TRIGGER_ORDER"),
                eq("surprising.perp.trigger-order.events.v1"), any(Timestamp.class), eq(25),
                any(Timestamp.class), any(Timestamp.class))).thenReturn(List.of());

        repository.claimPending(25, Instant.parse("2026-07-01T00:00:30Z"),
                Instant.parse("2026-07-01T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq("TRIGGER_ORDER"),
                eq("surprising.perp.trigger-order.events.v1"), eq("TRIGGER_ORDER"),
                eq("surprising.perp.trigger-order.events.v1"), any(Timestamp.class), eq(25),
                any(Timestamp.class), any(Timestamp.class));
        assertThat(sql.getValue())
                .contains("aggregate_type = ?")
                .contains("topic = ?")
                .contains("pg_try_advisory_xact_lock")
                .contains("FOR UPDATE OF e SKIP LOCKED");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
