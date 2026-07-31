package com.surprising.risk.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CachedRiskGroup;
import com.surprising.risk.provider.model.CachedRiskPosition;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import tools.jackson.databind.ObjectMapper;

class RedisRiskStateStoreTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void locksAuthoritativeLoadAndAtomicallyReplacesStateAndReverseIndexes() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        AtomicBoolean leaseGranted = new AtomicBoolean();
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenAnswer(invocation -> {
            leaseGranted.set(true);
            return true;
        });
        when(values.get("surprising:risk-state:v2:rebuild:LINEAR_PERPETUAL"))
                .thenReturn("generation-1");
        when(sets.members("surprising:risk-state:v2:memberships:LINEAR_PERPETUAL:"
                + "1001|USDT_PERPETUAL|USDT"))
                .thenReturn(Set.of("surprising:risk-state:v2:index:LINEAR_PERPETUAL:ETH-USDT:3"));
        when(redis.execute(any(DefaultRedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
        RedisRiskStateStore store = new RedisRiskStateStore(redis, new ObjectMapper(), new RiskProperties());
        RiskGroupKey key = new RiskGroupKey(1001L, "USDT_PERPETUAL", "USDT");
        AtomicBoolean loadedAfterLock = new AtomicBoolean();

        RedisRiskStateStore.ProjectionUpdate update = store.replace(
                ProductLine.LINEAR_PERPETUAL,
                key,
                () -> {
                    loadedAfterLock.set(leaseGranted.get());
                    return group(key);
                });

        assertThat(loadedAfterLock).isTrue();
        assertThat(update.changed()).isTrue();
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(redis, times(3)).execute(
                any(DefaultRedisScript.class), keys.capture(), arguments.capture());
        int replaceCall = 0;
        for (int i = 0; i < keys.getAllValues().size(); i++) {
            if (keys.getAllValues().get(i).getFirst().contains(":state:")) {
                replaceCall = i;
                break;
            }
        }
        assertThat(keys.getAllValues().get(replaceCall)).contains(
                "surprising:risk-state:v2:state:LINEAR_PERPETUAL:1001|USDT_PERPETUAL|USDT",
                "surprising:risk-state:v2:index:LINEAR_PERPETUAL:ETH-USDT:3",
                "surprising:risk-state:v2:index:LINEAR_PERPETUAL:BTC-USDT:7",
                "surprising:risk-state:v2:rebuild-seen:LINEAR_PERPETUAL:generation-1");
        assertThat(arguments.getAllValues().get(replaceCall)[0])
                .isEqualTo("1001|USDT_PERPETUAL|USDT");
    }

    private CachedRiskGroup group(RiskGroupKey key) {
        CachedRiskPosition position = new CachedRiskPosition(
                "BTC-USDT", MarginMode.CROSS, PositionSide.NET, 7L, "USDT",
                10L, 60_000L, 20_000L);
        return new CachedRiskGroup(
                key, 1_000_000L, List.of(position), Instant.parse("2026-07-31T00:00:00Z"));
    }
}
