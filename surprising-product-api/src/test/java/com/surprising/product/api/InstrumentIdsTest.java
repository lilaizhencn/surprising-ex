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
            if (value != null) {
                byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                assertThatThrownBy(() -> InstrumentIds.parse(bytes, 0, bytes.length))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test void acceptsByteArrayDecimalIds() {
        byte[] buffer = "prefix_12345_suffix".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(InstrumentIds.parse(buffer, 7, 5)).isEqualTo(12345);
        assertThat(InstrumentIds.canonicalString(123)).isEqualTo("123");
        assertThat(InstrumentIds.canonicalString(123)).isSameAs(InstrumentIds.canonicalString(123));
        assertThat(InstrumentIds.canonicalString(999999)).isEqualTo("999999");
    }
}
