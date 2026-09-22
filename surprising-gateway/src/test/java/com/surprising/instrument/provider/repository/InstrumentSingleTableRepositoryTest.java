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
    void auditSequenceDoesNotReadCurrentConfiguration() {
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class))).thenReturn(8L);
        assertThat(new InstrumentChangeLogRepository(jdbcTemplate).nextId()).isEqualTo(8L);
        verify(jdbcTemplate).queryForObject("SELECT nextval('instrument_change_log_sequence')", Long.class);
    }

    @Test
    void auditPreservesActorReasonAndBeforeAfterValues() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        new InstrumentChangeLogRepository(jdbcTemplate).append(ProductLine.LINEAR_DELIVERY,
                "BTC-USDT-260327", 4L, "operator-7", "maintenance", now,
                "{\"status\":\"TRADING\"}", "{\"status\":\"HALT\"}");
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(any(String.class), args.capture());
        assertThat(args.getValue()).containsExactly("LINEAR_DELIVERY", "BTC-USDT-260327", 4L,
                "operator-7", "maintenance", Timestamp.from(now),
                "{\"status\":\"TRADING\"}", "{\"status\":\"HALT\"}");
    }
}
