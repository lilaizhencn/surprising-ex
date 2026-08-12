package com.surprising.adl.provider.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.repository.AdlRepository;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class AdlRedisIndexCoordinatorTest {

    @Test
    void isolatesRebuildLockAndReadyStateByProductLine() {
        AdlProperties properties = new AdlProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        AdlRepository repository = mock(AdlRepository.class);
        RedisAdlCandidateIndex index = mock(RedisAdlCandidateIndex.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(eq("surprising:adl:v1:rebuild-lock:INVERSE_PERPETUAL"), anyString(),
                eq(Duration.ofSeconds(30)))).thenReturn(true);
        when(repository.candidateAssets()).thenReturn(List.of("USDT"));
        when(repository.queue(eq("USDT"), eq(5_000), any(Duration.class))).thenReturn(List.of());
        AdlRedisIndexCoordinator coordinator = new AdlRedisIndexCoordinator(repository, index, redis, properties);

        coordinator.rebuild();

        verify(index).clear(ProductLine.INVERSE_PERPETUAL, "USDT");
        verify(index).markReady(ProductLine.INVERSE_PERPETUAL, "USDT");
        verify(redis).execute(any(), eq(List.of("surprising:adl:v1:rebuild-lock:INVERSE_PERPETUAL")), anyString());
    }
}
