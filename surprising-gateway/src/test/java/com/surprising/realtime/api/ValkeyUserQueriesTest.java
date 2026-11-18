package com.surprising.realtime.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.*;

class ValkeyUserQueriesTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final ZSetOperations<String, String> requests = mock(ZSetOperations.class);
    private final Map<Object, Object> fields = new HashMap<>();
    private ValkeyUserQueries queries;

    @BeforeEach
    void setUp() {
        when(redis.opsForHash()).thenReturn(hashes);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForZSet()).thenReturn(requests);
        when(values.get("rt:source:SPOT")).thenReturn("source");
        when(hashes.entries("rt:view:{SPOT:42}")).thenAnswer(ignored -> new HashMap<>(fields));
        when(hashes.multiGet(eq("rt:view:{SPOT:42}"), anyCollection())).thenAnswer(call -> {
            Collection<?> selected = call.getArgument(1);
            return selected.stream().map(fields::get).toList();
        });
        fields.put("@fence", RealtimeVersion.fence(100));
        fields.put("@epoch", "source");
        fields.put("@snapshotAt", "1");
        fields.put("@exportSequence", RealtimeVersion.of(7, 0));
        var user = new CoreUserStateView(ProductLine.SPOT, 42, 1,
                List.of(new CoreBalanceView("USDT", 7, 0)), List.of(), List.of());
        var frame = new RealtimeFrame(ProductLine.SPOT, RealtimeFrame.Kind.USER,
                42, 100, 1, 1, 9, "", "user", CoreStateQueryCodec.encodeUserState(user));
        fields.put("USER:user", Base64.getEncoder().encodeToString(RealtimeFrameCodec.encode(frame)));
        queries = new ValkeyUserQueries(redis);
    }

    @Test
    void unchangedOldSnapshotRemainsReadableInFullAndPointQueries() {
        assertThat(System.currentTimeMillis() - 1).isGreaterThan(15_000);
        var full = queries.require(ProductLine.SPOT, 42, 7L);
        assertThat(full.status()).isEqualTo("READY");
        assertThat(full.snapshotAt()).isEqualTo(1);
        assertThat(full.account().balances().getFirst().availableUnits()).isEqualTo(7);
        assertThat(queries.balance(ProductLine.SPOT, 42, "USDT", 7L))
                .get().extracting(CoreBalanceView::availableUnits).isEqualTo(7L);
    }

    @Test
    void missingBaselineStillRejectsBothQueries() {
        fields.remove("@fence");
        rejectsBoth("READ_VIEW_INITIALIZING", null);
        verify(requests, times(2)).add(eq("rt:refresh:SPOT"), eq("42"), anyDouble());
    }

    @Test
    void changedSourceEpochStillRejectsBothQueries() {
        when(values.get("rt:source:SPOT")).thenReturn("replacement");
        rejectsBoth("READ_VIEW_STALE", null);
    }

    @Test
    void explicitlyInvalidSnapshotStillRejectsBothQueries() {
        fields.put("@invalid", "snapshot_unavailable");
        rejectsBoth("READ_VIEW_STALE", null);
    }

    @Test
    void unmetTargetSequenceStillRejectsBothQueries() {
        rejectsBoth("READ_VIEW_BEHIND", 8L);
    }

    private void rejectsBoth(String error, Long minimum) {
        assertThatThrownBy(() -> queries.require(ProductLine.SPOT, 42, minimum))
                .isInstanceOf(ReadViewUnavailableException.class).hasMessage(error);
        assertThatThrownBy(() -> queries.balance(ProductLine.SPOT, 42, "USDT", minimum))
                .isInstanceOf(ReadViewUnavailableException.class).hasMessage(error);
    }
}
