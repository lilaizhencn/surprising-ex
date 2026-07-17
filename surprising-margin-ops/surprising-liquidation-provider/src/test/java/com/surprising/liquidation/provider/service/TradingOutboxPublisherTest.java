package com.surprising.liquidation.provider.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.TradingOutboxRecord;
import com.surprising.liquidation.provider.repository.LiquidationOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class TradingOutboxPublisherTest {

    @Test
    void leasesRowsBeforeWaitingForKafkaAndMarksSuccessfulPublish() {
        LiquidationProperties properties = new LiquidationProperties();
        LiquidationOrderRepository repository = mock(LiquidationOrderRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        TradingOutboxRecord row = new TradingOutboxRecord(91L, "surprising.perp.order.commands.v1",
                "BTC-USDT", "{\"type\":\"PLACE\"}");
        when(repository.claimPending(eq(properties.getOutbox().getBatchSize()), any(), any()))
                .thenReturn(List.of(row));
        when(kafka.send(row.topic(), row.eventKey(), row.payload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        new TradingOutboxPublisher(properties, repository, kafka).publishPending();

        verify(repository).claimPending(eq(properties.getOutbox().getBatchSize()), any(Instant.class), any(Instant.class));
        verify(kafka).send(row.topic(), row.eventKey(), row.payload());
        verify(repository).markPublished(eq(91L), any(Instant.class));
    }
}
