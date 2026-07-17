package com.surprising.price.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MarkPriceKafkaConsumerTest {

    @Test
    void cachesBusinessResultFromCompletePublication() throws Exception {
        MarkPriceConsumerProperties properties = new MarkPriceConsumerProperties();
        LatestMarkPriceCache cache = new LatestMarkPriceCache(properties);
        MarkPriceKafkaConsumer consumer = new MarkPriceKafkaConsumer(new ObjectMapper(), cache, properties);
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

        consumer.onMarkPrice(new ConsumerRecord<>(properties.resolvedTopic(), 0, 0L, "BTC-USDT",
                new ObjectMapper().writeValueAsString(publication)));

        assertThat(cache.latest("BTC-USDT")).contains(result);
    }
}
