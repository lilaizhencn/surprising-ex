package com.surprising.price.index.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.api.model.PriceStatus;
import com.surprising.price.index.service.IndexPriceAuditService;
import com.surprising.price.index.repository.IndexPriceTickRepository.TickKey;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

class IndexPriceRepositoryBoundaryTest {

    @Test
    void savesAuditEventsInBatches() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.batchUpdate(any(String.class), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[] {1});
        IndexPriceTickRepository repository = new IndexPriceTickRepository(jdbcTemplate);

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
        IndexPriceTickRepository ticks = mock(IndexPriceTickRepository.class);
        IndexPriceComponentRepository components = mock(IndexPriceComponentRepository.class);
        Instant cutoff = Instant.parse("2026-07-14T00:00:00Z");
        List<TickKey> keys = List.of(new TickKey("BTC-USDT", 7L));
        when(ticks.findExpiredForDeletion(cutoff, 100)).thenReturn(keys);
        when(ticks.deleteByKeys(keys)).thenReturn(1);
        IndexPriceAuditService service = new IndexPriceAuditService(ticks, components);

        assertThat(service.deleteBefore(cutoff, 100)).isEqualTo(1);
        InOrder order = inOrder(ticks, components);
        order.verify(ticks).findExpiredForDeletion(cutoff, 100);
        order.verify(components).deleteByKeys(keys);
        order.verify(ticks).deleteByKeys(keys);
    }

    @Test
    void tickDeletionOnlyTouchesTickTable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);
        IndexPriceTickRepository repository = new IndexPriceTickRepository(jdbcTemplate);

        repository.deleteByKeys(List.of(new TickKey("BTC-USDT", 7L)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("DELETE FROM price_index_ticks")
                .doesNotContain("price_index_components");
    }

    @Test
    void componentDeletionOnlyTouchesComponentTable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);
        IndexPriceComponentRepository repository = new IndexPriceComponentRepository(jdbcTemplate);

        repository.deleteByKeys(List.of(new TickKey("BTC-USDT", 7L)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("DELETE FROM price_index_components")
                .doesNotContain("price_index_ticks");
    }

    private static IndexPriceEvent event() {
        return new IndexPriceEvent("BTC-USDT", new BigDecimal("100"), 7, PriceStatus.HEALTHY,
                3, 3, BigDecimal.valueOf(3), Instant.parse("2026-07-17T00:00:00Z"), List.of());
    }
}
