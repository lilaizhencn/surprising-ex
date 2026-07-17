package com.surprising.price.index.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.PriceStatus;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

class IndexPriceRepositoryTest {

    @Test
    void savesAuditEventsInBatches() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[] {1});
        IndexPriceRepository repository = new IndexPriceRepository(jdbcTemplate);

        repository.saveBatch(List.of(event()));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BatchPreparedStatementSetter> setter = ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(sql.capture(), setter.capture());
        assertThat(sql.getValue()).contains("price_index_ticks").contains("ON CONFLICT");
        PreparedStatement statement = mock(PreparedStatement.class);
        setter.getValue().setValues(statement, 0);
        verify(statement).setString(1, "BTC-USDT");
        verify(statement).setLong(2, 7L);
        verify(statement).setTimestamp(8, Timestamp.from(event().eventTime()));
    }

    @Test
    void deletionRemovesComponentsBeforeTheBoundedTickBatch() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(10);
        IndexPriceRepository repository = new IndexPriceRepository(jdbcTemplate);

        assertThat(repository.deleteAuditBefore(Instant.parse("2026-07-14T00:00:00Z"), 100)).isEqualTo(10);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("deleted_components").contains("LIMIT ?");
    }

    private static IndexPriceEvent event() {
        return new IndexPriceEvent("BTC-USDT", new BigDecimal("100"), 7, PriceStatus.HEALTHY,
                3, 3, BigDecimal.valueOf(3), Instant.parse("2026-07-17T00:00:00Z"), List.of());
    }
}
