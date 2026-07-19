package com.surprising.price.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    @Test
    void discardsMalformedPublicationWithoutBlockingThePartition() {
        MarkPriceConsumerProperties properties = new MarkPriceConsumerProperties();
        LatestMarkPriceCache cache = new LatestMarkPriceCache(properties);
        MarkPriceKafkaConsumer consumer = new MarkPriceKafkaConsumer(new ObjectMapper(), cache, properties);

        assertThatCode(() -> consumer.onMarkPrice(new ConsumerRecord<>(properties.resolvedTopic(), 0, 0L,
                "BTC-USDT", "{not-json"))).doesNotThrowAnyException();

        assertThat(cache.latest("BTC-USDT")).isEmpty();
    }

    @Test
    void notifiesListenersForEveryAcceptedUpdateAfterWarmup() throws Exception {
        MarkPriceConsumerProperties properties = new MarkPriceConsumerProperties();
        LatestMarkPriceCache cache = new LatestMarkPriceCache(properties);
        MarkPriceUpdateListener listener = mock(MarkPriceUpdateListener.class);
        MarkPriceKafkaConsumer consumer = new MarkPriceKafkaConsumer(
                new ObjectMapper(), cache, properties, List.of(listener));
        MarkPriceEvent first = mark(590_000L, 1L);
        MarkPriceEvent same = mark(590_000L, 2L);
        MarkPriceEvent changed = mark(580_000L, 3L);

        publish(consumer, properties, first);
        publish(consumer, properties, same);
        publish(consumer, properties, changed);

        verify(listener).onMarkPriceUpdated(first, same);
        verify(listener).onMarkPriceUpdated(same, changed);
        verifyNoMoreInteractions(listener);
    }

    private void publish(MarkPriceKafkaConsumer consumer,
                         MarkPriceConsumerProperties properties,
                         MarkPriceEvent event) throws Exception {
        MarkPricePublishedEvent publication = new MarkPricePublishedEvent(event, null, null, null, null,
                BigDecimal.ZERO, 60L, event.eventTime());
        consumer.onMarkPrice(new ConsumerRecord<>(properties.resolvedTopic(), 0, event.sequence(), event.symbol(),
                new ObjectMapper().writeValueAsString(publication)));
    }

    private MarkPriceEvent mark(long ticks, long sequence) {
        Instant now = Instant.now();
        BigDecimal price = BigDecimal.valueOf(ticks, 1);
        return new MarkPriceEvent(ProductLine.LINEAR_PERPETUAL, "BTC-USDT", 1L,
                ticks * 10_000_000L, ticks, price, price, price, price, price,
                price, price, BigDecimal.ZERO, now.plusSeconds(3600), 3600L, BigDecimal.ZERO, 60L,
                price, price, sequence, PriceStatus.HEALTHY, now, now);
    }
}
