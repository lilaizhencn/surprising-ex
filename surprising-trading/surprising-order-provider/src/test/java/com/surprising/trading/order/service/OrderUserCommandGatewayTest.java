package com.surprising.trading.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PositionUpdatedEvent;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OrderUserCommandGatewayTest {

    @Test
    void positionEventIdSeparatesReduceOnlyCommands() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        TradingOrderProperties properties = new TradingOrderProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        OrderUserCommandGateway gateway = new OrderUserCommandGateway(
                objectMapper, properties, kafka, mock(OrderUserCommandResultWaiter.class));

        gateway.pruneReduceOnly(event(91L, 7L), "position-reduced");
        gateway.pruneReduceOnly(event(92L, 8L), "position-reduced");

        ArgumentCaptor<String> payloads = ArgumentCaptor.forClass(String.class);
        verify(kafka, times(2)).send(eq(properties.getKafka().getOrderUserCommandsTopic()),
                eq("LINEAR_PERPETUAL:1001"), payloads.capture());
        JsonNode first = objectMapper.readTree(payloads.getAllValues().get(0));
        JsonNode second = objectMapper.readTree(payloads.getAllValues().get(1));
        assertThat(first.get("commandId").asText()).endsWith(":91");
        assertThat(second.get("commandId").asText()).endsWith(":92");
        assertThat(first.get("commandId").asText()).isNotEqualTo(second.get("commandId").asText());
    }

    private PositionUpdatedEvent event(long eventId, long revision) {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        return new PositionUpdatedEvent(
                PositionUpdatedEvent.CURRENT_SCHEMA_VERSION, eventId, 8001L, ProductLine.LINEAR_PERPETUAL,
                revision, 1001L, "BTC-USDT", 7L, MarginMode.CROSS, PositionSide.NET,
                2L, 65_000L, 65_000L, 0L, "USDT", 100L, now, now, now, "trace-position-prune");
    }
}
