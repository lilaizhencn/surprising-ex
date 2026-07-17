package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OrderMarkPriceRepositoryTest {

    @Test
    void readsFreshVersionedFixedPointTicksFromKafkaCache() {
        LatestMarkPriceCache cache = mock(LatestMarkPriceCache.class);
        MarkPriceEvent event = mock(MarkPriceEvent.class);
        when(event.instrumentVersion()).thenReturn(7L);
        when(event.markPriceTicks()).thenReturn(65_001L);
        when(cache.fresh("BTC-USDT", Duration.ofSeconds(5))).thenReturn(Optional.of(event));
        OrderMarkPriceRepository repository = new OrderMarkPriceRepository(cache);

        assertThat(repository.latestMarkPriceTicks("BTC-USDT", 7L, 5_000L)).hasValue(65_001L);
        verify(cache).fresh("BTC-USDT", Duration.ofSeconds(5));
    }

    @Test
    void rejectsMarkPriceEncodedForDifferentInstrumentVersion() {
        LatestMarkPriceCache cache = mock(LatestMarkPriceCache.class);
        MarkPriceEvent event = mock(MarkPriceEvent.class);
        when(event.instrumentVersion()).thenReturn(8L);
        when(cache.fresh("BTC-USDT", Duration.ofMillis(1))).thenReturn(Optional.of(event));
        OrderMarkPriceRepository repository = new OrderMarkPriceRepository(cache);

        assertThat(repository.latestMarkPriceTicks("BTC-USDT", 7L, 0L)).isEmpty();
    }
}
