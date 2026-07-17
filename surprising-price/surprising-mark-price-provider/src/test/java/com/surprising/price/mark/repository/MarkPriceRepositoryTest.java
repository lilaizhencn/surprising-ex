package com.surprising.price.mark.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.api.model.MarkPricePublishedEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.mark.model.MarkPriceAuditRecord;
import com.surprising.product.api.ProductLine;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class MarkPriceRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void saveBatchPersistsFixedPointResultAndCompleteAuditJson() throws Exception {
        MarkPriceRepository repository = new MarkPriceRepository(jdbcTemplate);
        when(jdbcTemplate.batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[] {1});

        repository.saveBatch(List.of(new MarkPriceAuditRecord(auditEvent(), "{\"result\":{}}")));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BatchPreparedStatementSetter> setter =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(sql.capture(), setter.capture());
        assertThat(sql.getValue())
                .contains("mark_price_units")
                .contains("mark_price_ticks")
                .contains("calculation_inputs")
                .contains("ON CONFLICT DO NOTHING");

        PreparedStatement statement = mock(PreparedStatement.class);
        setter.getValue().setValues(statement, 0);
        verify(statement).setString(1, ProductLine.LINEAR_PERPETUAL.name());
        verify(statement).setString(2, "BTC-USDT");
        verify(statement).setLong(3, 7L);
        verify(statement).setLong(6, 5_900_000_000_000L);
        verify(statement).setLong(7, 5_900_000L);
        verify(statement).setTimestamp(22, Timestamp.from(auditEvent().result().eventTime()));
        verify(statement).setString(24, "{\"result\":{}}");
        assertThat(setter.getValue().getBatchSize()).isEqualTo(1);
    }

    @Test
    void cleanupDeletesOnlyOneBoundedBatch() {
        MarkPriceRepository repository = new MarkPriceRepository(jdbcTemplate);
        Instant cutoff = Instant.parse("2026-07-14T00:00:00Z");
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(250);

        int deleted = repository.deleteAuditBefore(cutoff, 10_000);

        assertThat(deleted).isEqualTo(250);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertThat(sql.getValue()).contains("event_time < ?").contains("LIMIT ?");
        assertThat(args.getValue()).containsExactly(Timestamp.from(cutoff), 10_000);
    }

    private static MarkPricePublishedEvent auditEvent() {
        MarkPriceEvent event = event();
        return new MarkPricePublishedEvent(event, null, null, null, null,
                event.basisAverage(), event.basisWindowSeconds(), event.eventTime());
    }

    private static MarkPriceEvent event() {
        Instant now = Instant.parse("2026-07-17T00:00:00Z");
        return new MarkPriceEvent(ProductLine.LINEAR_PERPETUAL, "BTC-USDT", 7L,
                5_900_000_000_000L, 5_900_000L, new BigDecimal("59000.00"),
                new BigDecimal("59000.00"), new BigDecimal("59000.00"),
                new BigDecimal("59000.00"), new BigDecimal("59000.00"),
                new BigDecimal("58999.00"), new BigDecimal("59001.00"), BigDecimal.ZERO,
                now.plusSeconds(3600), 3600L, BigDecimal.ZERO, 60L,
                new BigDecimal("58000.00"), new BigDecimal("60000.00"), 9901L,
                PriceStatus.HEALTHY, now, now);
    }
}
