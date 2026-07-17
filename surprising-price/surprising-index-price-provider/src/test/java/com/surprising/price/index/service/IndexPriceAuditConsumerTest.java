package com.surprising.price.index.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.index.config.IndexPriceProperties;
import com.surprising.price.index.repository.IndexPriceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class IndexPriceAuditConsumerTest {

    @Test
    void persistsTheSameCompleteEventFromTheBusinessTopic() throws Exception {
        IndexPriceRepository repository = mock(IndexPriceRepository.class);
        IndexPriceProperties properties = new IndexPriceProperties();
        IndexPriceAuditConsumer consumer = new IndexPriceAuditConsumer(new ObjectMapper(), repository, properties);
        IndexPriceEvent event = new IndexPriceEvent("BTC-USDT", new BigDecimal("100"), 7,
                PriceStatus.HEALTHY, 3, 3, BigDecimal.valueOf(3), Instant.now(), List.of());
        String payload = new ObjectMapper().writeValueAsString(event);

        consumer.onAudit(List.of(new ConsumerRecord<>(properties.getKafka().getIndexPriceTopic(), 0, 0L,
                event.symbol(), payload)));

        verify(repository).saveBatch(eq(List.of(event)));
    }
}
