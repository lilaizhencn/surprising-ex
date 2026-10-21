package com.surprising.aeron.service.state.fee;

import com.surprising.aeron.service.state.model.CoreFeePolicyState;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class FeePolicyBookTest {
    @Test void immutableVersionsKeepPriorityExpiryAndRevisionSemantics() {
        var userWide = policy(1, 1, "", 3, 100, 0, 10);
        var symbol = policy(2, 1, "1", 3, 100, 300, 20);
        var latest = policy(3, 1, "1", 3, 150, 200, 30);
        var original = FeePolicyBook.from(Map.of(1L, userWide, 2L, symbol, 3L, latest));
        assertThat(original.select(7, "1", 180)).isSameAs(latest);
        assertThat(original.select(7, "1", 200)).isSameAs(symbol);
        assertThat(original.select(7, "1", 300)).isSameAs(userWide);
        assertThat(original.select(8, "1", 180)).isNull();
        assertThat(original.select(7, "2", 180)).isSameAs(userWide);
        var higherPriority = policy(4, 1, "", 0, 100, 0, 40);
        var changed = original.upsert(higherPriority);
        assertThat(changed.select(7, "1", 180)).isSameAs(higherPriority);
        assertThat(original.select(7, "1", 180)).isSameAs(latest);
        assertThat(changed.upsert(higherPriority)).isSameAs(changed);
        assertThatThrownBy(() -> changed.upsert(policy(4, 1, "", 0, 100, 0, 41)))
                .hasMessageContaining("different data");
        var revised = changed.upsert(policy(4, 2, "", 0, 100, 0, 42));
        assertThat(revised.select(7, "1", 180).makerFeeRatePpm()).isEqualTo(42);
        assertThatThrownBy(() -> revised.upsert(higherPriority)).hasMessageContaining("version must increase");
        assertThat(changed.snapshot()).hasSize(4);
        assertThatThrownBy(() -> FeePolicyBook.from(Map.of(9L, latest)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static CoreFeePolicyState policy(long id, long revision, String symbol, int priority,
            long start, long expiry, long fee) {
        return new CoreFeePolicyState(id, revision, 7, symbol, fee, fee, priority, true, start, expiry);
    }
}
