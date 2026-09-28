package com.surprising.product.api;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class InstrumentIdsTest {
    @Test void acceptsPermanentPositiveIntegerIds() {
        assertThat(InstrumentIds.parse("1")).isEqualTo(1);
        assertThat(InstrumentIds.parse("2147483647")).isEqualTo(Integer.MAX_VALUE);
    }

    @Test void rejectsNamesNonCanonicalNumbersAndOverflow() {
        for (String value : new String[]{null, "", "0", "01", " 1", "1 ", "+1", "-1", "1.0",
                "BTC-USDT", "BTC-USDT-SWAP", "2147483648", "99999999999", "١"}) {
            assertThat(InstrumentIds.valid(value)).as("value=%s", value).isFalse();
            assertThatThrownBy(() -> InstrumentIds.parse(value)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
