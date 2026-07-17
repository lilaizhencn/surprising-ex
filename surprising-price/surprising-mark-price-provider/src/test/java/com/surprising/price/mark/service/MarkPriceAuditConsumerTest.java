package com.surprising.price.mark.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.mark.config.MarkPriceProperties;
import com.surprising.price.mark.model.MarkPriceAuditRecord;
import com.surprising.price.mark.repository.MarkPriceRepository;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class MarkPriceAuditConsumerTest {

    @Test
    void persistsTheCompletePublicationFromTheBusinessTopic() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MarkPriceRepository repository = mock(MarkPriceRepository.class);
        MarkPriceProperties properties = new MarkPriceProperties();
        MarkPriceAuditConsumer consumer = new MarkPriceAuditConsumer(objectMapper, repository, properties);
        Instant now = Instant.now();
        BigDecimal price = new BigDecimal("59000");
        MarkPriceEvent result = new MarkPriceEvent(ProductLine.LINEAR_PERPETUAL, "BTC-USDT", 1L,
                5_900_000_000_000L, 590_000L, price, price, price, price, price,
                new BigDecimal("58999"), new BigDecimal("59001"), BigDecimal.ZERO,
                now.plusSeconds(3600), 3600L, BigDecimal.ZERO, 60L,
                new BigDecimal("57000"), new BigDecimal("61000"), 1L,
                PriceStatus.HEALTHY, now, now);
        MarkPricePublishedEvent publication = new MarkPricePublishedEvent(result, null, null, null, null,
                BigDecimal.ZERO, 60L, now);
        String payload = objectMapper.writeValueAsString(publication);

        consumer.onAudit(List.of(new ConsumerRecord<>(properties.markPriceTopic(), 0, 0L,
                result.symbol(), payload)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MarkPriceAuditRecord>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveBatch(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(record -> {
            assertThat(record.event()).isEqualTo(publication);
            assertThat(record.payloadJson()).isEqualTo(payload);
        });
        assertThat(consumer.markPriceTopic()).isEqualTo(properties.markPriceTopic());
        assertThat(consumer.groupId()).isEqualTo(properties.getKafka().getGroupId() + "-audit-writer");
    }
}
