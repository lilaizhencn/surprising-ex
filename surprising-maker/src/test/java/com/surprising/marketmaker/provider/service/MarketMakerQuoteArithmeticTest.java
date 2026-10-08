package com.surprising.marketmaker.provider.service;

import static org.assertj.core.api.Assertions.*;
import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MarketMakerQuoteArithmeticTest {
    @Test
    void preservesExactPpmRoundingAtLongBoundaries() {
        var random = new Random(25620);
        for (int i = 0; i < 10000; i++) {
            long value = i == 0 ? Long.MAX_VALUE : random.nextLong() & Long.MAX_VALUE;
            long ppm = i == 0 ? 1_000_000 : random.nextInt(1_000_001);
            long expected = BigInteger.valueOf(value).multiply(BigInteger.valueOf(ppm))
                    .divide(BigInteger.valueOf(1_000_000)).longValueExact();
            assertThat(MarketMakerService.ppmFloor(value, ppm)).isEqualTo(expected);
        }
        assertThat(MarketMakerService.ppmFloor(9999, 100)).isZero();
        assertThat(MarketMakerService.ppmFloor(10000, 100)).isEqualTo(1);
        assertThatThrownBy(() -> MarketMakerService.ppmFloor(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MarketMakerService.ppmFloor(1, 1_000_001)).isInstanceOf(IllegalArgumentException.class);
    }
}
