package com.surprising.realtime.api;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RealtimeVersionEncodingTest {
    @Test void preservesDecimalPaddingOrderingAndAllLongPositions() {
        for (long sequence : new long[]{0, 1, 9, 10, 99, 100, Integer.MAX_VALUE, Long.MAX_VALUE})
            for (int ordinal : new int[]{0, 1, 9, 10, 99, 100, Integer.MAX_VALUE}) verify(sequence, ordinal);
        var random = new Random(20261008);
        for (int i = 0; i < 1000; i++) {
            long sequence = random.nextLong() & Long.MAX_VALUE;
            int ordinal = random.nextInt() & Integer.MAX_VALUE;
            verify(sequence, ordinal);
            if (sequence < Long.MAX_VALUE)
                assertThat(RealtimeVersion.of(sequence, ordinal).compareTo(RealtimeVersion.of(sequence + 1, 0))).isNegative();
        }
        assertThat(RealtimeVersion.fence(Long.MAX_VALUE)).isEqualTo("9223372036854775807:2147483647");
        assertThatThrownBy(() -> RealtimeVersion.of(-1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RealtimeVersion.bytes(0, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    private static void verify(long sequence, int ordinal) {
        String expected = String.format(Locale.ROOT, "%019d:%010d", sequence, ordinal);
        assertThat(RealtimeVersion.of(sequence, ordinal)).isEqualTo(expected);
        assertThat(RealtimeVersion.bytes(sequence, ordinal)).containsExactly(expected.getBytes(StandardCharsets.US_ASCII));
    }
}
