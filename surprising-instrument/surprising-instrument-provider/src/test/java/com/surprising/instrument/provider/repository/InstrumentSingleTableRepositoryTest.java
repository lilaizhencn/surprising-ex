package com.surprising.instrument.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class InstrumentSingleTableRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @Test
    void sequenceAllocationTouchesOnlySequenceTable() {
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class), any(Object[].class)))
                .thenReturn(8L);

        long version = new InstrumentSequenceRepository(jdbcTemplate).next("BTC-USDT", 8L);

        assertThat(version).isEqualTo(8L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), any(Class.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("instrument_symbol_sequences")
                .doesNotContain(" FROM instruments");
    }

    @Test
    void productCurrentVersionWritesOnlyProductPointerTable() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        new InstrumentProductCurrentVersionRepository(jdbcTemplate)
                .set(ProductLine.LINEAR_DELIVERY, "BTC-USDT-260327", 4L, now);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("instrument_product_current_versions")
                .doesNotContain(" instruments ");
        assertThat(args.getValue()).containsExactly(
                "LINEAR_DELIVERY", "BTC-USDT-260327", 4L, Timestamp.from(now));
    }
}
