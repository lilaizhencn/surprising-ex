package com.surprising.trading.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.product.api.ProductLine;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ProjectedOrderQueryRepositoryTest {

    @Test
    void boundsLagCursorAndEncodedBytesWithoutCoreFallback() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), eq(ProductLine.SPOT.name())))
                .thenReturn(12L);
        AtomicReference<String> sql = new AtomicReference<>();
        doAnswer(invocation -> {
            sql.set(invocation.getArgument(0));
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getLong("order_id")).thenReturn(91L);
            when(resultSet.getLong("updated_at_epoch_ms")).thenReturn(1_700_000_000_100L);
            when(resultSet.getBytes("raw_order_state")).thenReturn(rawOrder(91L));
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(any(String.class), any(RowMapper.class), any(Object[].class));

        AeronOrderProjectionRepository repository = new AeronOrderProjectionRepository(jdbcTemplate,
                new ProjectionWatermarkWaiter(jdbcTemplate), 1_048_576);
        ProjectionReadResult result = repository.openOrders(ProductLine.SPOT, 1001L, "BTC-USDT", null, 1, 12L);

        assertThat(result.status()).isEqualTo(ProjectionReadResult.Status.OK);
        assertThat(result.orders()).hasSize(1);
        assertThat(result.observedExportSequence()).isEqualTo(12L);
        assertThat(AeronOrderProjectionRepository.decodeCursor(result.nextCursor())).isNull();
        assertThat(sql).hasValueSatisfying(value -> {
            assertThat(value).contains("product_line = ?", "user_id = ?", "status = 'OPEN'",
                    "updated_at_epoch_ms DESC", "order_id DESC", "LIMIT ?");
            assertThat(value).doesNotContain("FOR UPDATE", "ORDER_STATE_QUERY", "USER_OPEN_ORDERS_QUERY");
        });
        verify(jdbcTemplate).queryForObject(any(String.class), eq(Long.class), eq(ProductLine.SPOT.name()));
    }

    @Test
    void ordinaryProjectionReadDoesNotWaitWithoutMinimumExportSequence() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doAnswer(invocation -> List.of())
                .when(jdbcTemplate).query(any(String.class), any(RowMapper.class), any(Object[].class));

        AeronOrderProjectionRepository repository = new AeronOrderProjectionRepository(jdbcTemplate);
        ProjectionReadResult result = repository.openOrders(ProductLine.SPOT, 1001L, null, null, 1, null);

        assertThat(result.status()).isEqualTo(ProjectionReadResult.Status.OK);
        verify(jdbcTemplate, never()).queryForObject(any(String.class), eq(Long.class), eq(ProductLine.SPOT.name()));
    }

    @Test
    void projectionLagIsTypedAndStopsBeforeSelectingRows() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), eq(ProductLine.SPOT.name())))
                .thenReturn(3L);

        AeronOrderProjectionRepository repository = new AeronOrderProjectionRepository(jdbcTemplate);
        ProjectionReadResult result = repository.openOrders(ProductLine.SPOT, 1001L, null, null, 10, 9L);

        assertThat(result.status()).isEqualTo(ProjectionReadResult.Status.PROJECTION_LAG);
        assertThat(result.observedExportSequence()).isEqualTo(3L);
        assertThat(result.requiredExportSequence()).isEqualTo(9L);
        verify(jdbcTemplate, never()).query(any(String.class), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void stopsBeforeEncodedByteOverflowWithForwardCursor() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), eq(ProductLine.SPOT.name())))
                .thenReturn(12L);
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet(91L), 0), mapper.mapRow(resultSet(92L), 1));
        }).when(jdbcTemplate).query(any(String.class), any(RowMapper.class), any(Object[].class));

        AeronOrderProjectionRepository baseline = new AeronOrderProjectionRepository(jdbcTemplate,
                new ProjectionWatermarkWaiter(jdbcTemplate), 4 * 1024 * 1024);
        int oneRowBytes = baseline.openOrders(ProductLine.SPOT, 1001L, "BTC-USDT", null, 1, 12L)
                .encodedBytes();
        AeronOrderProjectionRepository bounded = new AeronOrderProjectionRepository(jdbcTemplate,
                new ProjectionWatermarkWaiter(jdbcTemplate), oneRowBytes);

        ProjectionReadResult result = bounded.openOrders(ProductLine.SPOT, 1001L, "BTC-USDT", null, 1000, 12L);

        assertThat(result.status()).isEqualTo(ProjectionReadResult.Status.OK);
        assertThat(result.orders()).hasSize(1);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.encodedBytes()).isLessThanOrEqualTo(oneRowBytes);
        assertThat(AeronOrderProjectionRepository.decodeCursor(result.nextCursor()))
                .isEqualTo(new AeronOrderProjectionRepository.Cursor(1_700_000_000_100L, 91L));
    }

    @Test
    void firstOversizedRowReturnsItsContinuationCursor() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), eq(ProductLine.SPOT.name())))
                .thenReturn(12L);
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(resultSet(91L), 0));
        }).when(jdbcTemplate).query(any(String.class), any(RowMapper.class), any(Object[].class));

        AeronOrderProjectionRepository baseline = new AeronOrderProjectionRepository(jdbcTemplate,
                new ProjectionWatermarkWaiter(jdbcTemplate), 4 * 1024 * 1024);
        int oneRowBytes = baseline.openOrders(ProductLine.SPOT, 1001L, "BTC-USDT", null, 1, 12L)
                .encodedBytes();
        AeronOrderProjectionRepository bounded = new AeronOrderProjectionRepository(jdbcTemplate,
                new ProjectionWatermarkWaiter(jdbcTemplate), oneRowBytes - 1);

        ProjectionReadResult result = bounded.openOrders(ProductLine.SPOT, 1001L, "BTC-USDT", null, 1000, 12L);

        assertThat(result.status()).isEqualTo(ProjectionReadResult.Status.RESPONSE_TOO_LARGE);
        assertThat(result.orders()).isEmpty();
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextCursor()).isNotBlank();
        assertThat(AeronOrderProjectionRepository.decodeCursor(result.nextCursor()))
                .isEqualTo(new AeronOrderProjectionRepository.Cursor(1_700_000_000_100L, 91L));
    }

    @Test
    void cursorEncodingIsStableForEqualTimestampsAndDifferentOrderIds() {
        String first = AeronOrderProjectionRepository.encodeCursor(1_700_000_000_100L, 91L);
        String second = AeronOrderProjectionRepository.encodeCursor(1_700_000_000_100L, 92L);

        assertThat(first).isNotEqualTo(second);
        assertThat(AeronOrderProjectionRepository.decodeCursor(first))
                .isEqualTo(new AeronOrderProjectionRepository.Cursor(1_700_000_000_100L, 91L));
        assertThat(AeronOrderProjectionRepository.decodeCursor(second))
                .isEqualTo(new AeronOrderProjectionRepository.Cursor(1_700_000_000_100L, 92L));
    }

    @Test
    void configuredEncodedBytesAreCappedAtFourMiB() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        assertThatThrownBy(() -> new AeronOrderProjectionRepository(jdbcTemplate, 4 * 1024 * 1024 + 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("4MiB");
    }

    private static byte[] rawOrder(long orderId) {
        CoreOrderStateView view = new CoreOrderStateView(orderId, ProductLine.SPOT, 1001L, "BTC-USDT", 7L,
                com.surprising.aeron.protocol.CoreOrderSide.BUY, 60_000L, 10L, 0L, 10L, false, "OPEN", 1L);
        return CoreStateQueryCodec.encodeOrderState(view);
    }

    private static ResultSet resultSet(long orderId) throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("order_id")).thenReturn(orderId);
        when(resultSet.getLong("updated_at_epoch_ms")).thenReturn(1_700_000_000_100L);
        when(resultSet.getBytes("raw_order_state")).thenReturn(rawOrder(orderId));
        return resultSet;
    }
}
