package com.surprising.aeron.benchmarks.workload;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GeneratedMarkPriceSourceTest {
    @Test void publishesActualEarlierObservationAndOwnsItsPrices() {
        long before = System.currentTimeMillis();
        long[] prices = {100, 1};
        try (var source = new GeneratedMarkPriceSource(prices)) {
            prices[0] = 999;
            for (int i = 0; i < 1000; i++) {
                var quote = source.quote(i & 1);
                assertThat(quote.priceTicks()).isEqualTo((i & 1) == 0 ? 100 : 1);
                assertThat(quote.generatedAtEpochMillis()).isBetween(before, System.currentTimeMillis());
            }
        }
    }

    @Test void closedSourceCannotSilentlySupplyStaleQuotes() {
        var source = new GeneratedMarkPriceSource(new long[]{100});
        source.close();
        assertThatThrownBy(() -> source.quote(0)).isInstanceOf(IllegalStateException.class);
    }

    @Test void invalidQuotesDoNotStartSource() {
        assertThatThrownBy(() -> new GeneratedMarkPriceSource(new long[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GeneratedMarkPriceSource(new long[]{0})).isInstanceOf(IllegalArgumentException.class);
    }
}
