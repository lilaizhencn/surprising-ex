package com.surprising.eventstore;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BoundedExpiringCacheTest {

    @Test
    void evictsLeastRecentlyUsedEntryWhenCapacityIsReached() {
        BoundedExpiringCache<String, Integer> cache = new BoundedExpiringCache<>(Duration.ofMinutes(1), 2);
        cache.put("a", 1);
        cache.put("b", 2);
        assertThat(cache.get("a")).isEqualTo(1);
        cache.put("c", 3);
        assertThat(cache.get("b")).isNull();
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void removesExpiredEntry() throws Exception {
        BoundedExpiringCache<String, Integer> cache = new BoundedExpiringCache<>(Duration.ofMillis(1), 2);
        cache.put("a", 1);
        Thread.sleep(5L);
        assertThat(cache.get("a")).isNull();
        assertThat(cache.size()).isZero();
    }
}
