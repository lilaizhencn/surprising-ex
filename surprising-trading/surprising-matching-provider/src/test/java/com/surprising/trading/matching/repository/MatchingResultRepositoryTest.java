package com.surprising.trading.matching.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.OrderSide;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class MatchingResultRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final MatchingResultRepository repository = new MatchingResultRepository(jdbcTemplate);

    @Test
    void commandStatesAreReadForTheWholePollInOneQuery() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(commandStateRow(7001L, false, true));
            handler.processRow(commandStateRow(7002L, true, true));
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        var states = repository.commandStates(java.util.Map.of(7001L, 8001L, 7002L, 8002L));

        assertThat(states).hasSize(2);
        assertThat(states.get(7001L).orderExists()).isTrue();
        assertThat(states.get(7002L).resultExists()).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowCallbackHandler.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("WITH input(command_id, order_id)")
                .contains("LEFT JOIN trading_match_results")
                .contains("LEFT JOIN trading_orders");
    }

    @Test
    void makerSnapshotsAreDeduplicatedIntoOneDatabaseRead() throws Exception {
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(orderRow(9001L, 10L, 7L, false));
            handler.processRow(orderRow(9002L, 20L, 5L, true));
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        var snapshots = repository.orderSnapshots(List.of(9001L, 9002L, 9001L));

        assertThat(snapshots).hasSize(2);
        assertThat(snapshots.get(9001L).remainingQuantitySteps()).isEqualTo(7L);
        assertThat(snapshots.get(9002L).reduceOnly()).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), any(RowCallbackHandler.class), args.capture());
        assertThat(sql.getValue()).contains("order_id IN (?, ?)");
        assertThat(args.getValue()).containsExactly(9001L, 9002L);
    }

    @Test
    void tradesAreInsertedWithOneJdbcBatch() {
        List<MatchTradeEvent> trades = List.of(trade(1L, 2L, false), trade(2L, 3L, true));
        when(jdbcTemplate.batchUpdate(anyString(), any(BatchPreparedStatementSetter.class)))
                .thenReturn(new int[]{1, 1});

        repository.saveTrades(trades);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BatchPreparedStatementSetter> setter =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        verify(jdbcTemplate).batchUpdate(sql.capture(), setter.capture());
        assertThat(sql.getValue())
                .contains("INSERT INTO trading_match_trades")
                .contains("ON CONFLICT (product_line, symbol, trade_id) DO NOTHING");
        assertThat(setter.getValue().getBatchSize()).isEqualTo(2);
    }

    @Test
    void repeatedMakerFillsAreAggregatedIntoOneSetBasedUpdate() {
        Instant first = Instant.parse("2026-07-19T00:00:00Z");
        Instant second = first.plusMillis(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        repository.applyMakerFills(List.of(
                trade(1L, 2L, false, first),
                trade(2L, 3L, true, second)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(Integer.class), args.capture());
        assertThat(sql.getValue())
                .contains("WITH input")
                .contains("UPDATE trading_orders")
                .contains("RETURNING o.order_id");
        assertThat(args.getValue()).containsExactly(
                9002L, 5L, "FILLED", java.sql.Timestamp.from(second));
    }

    private ResultSet orderRow(long orderId,
                               long quantitySteps,
                               long remainingQuantitySteps,
                               boolean reduceOnly) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("order_id")).thenReturn(orderId);
        when(rs.getLong("instrument_version")).thenReturn(1L);
        when(rs.getString("margin_mode")).thenReturn("CROSS");
        when(rs.getString("position_side")).thenReturn("NET");
        when(rs.getLong("quantity_steps")).thenReturn(quantitySteps);
        when(rs.getLong("remaining_quantity_steps")).thenReturn(remainingQuantitySteps);
        when(rs.getBoolean("reduce_only")).thenReturn(reduceOnly);
        return rs;
    }

    private ResultSet commandStateRow(long commandId, boolean resultExists, boolean orderExists) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("command_id")).thenReturn(commandId);
        when(rs.getBoolean("result_exists")).thenReturn(resultExists);
        when(rs.getBoolean("order_exists")).thenReturn(orderExists);
        return rs;
    }

    private MatchTradeEvent trade(long tradeId, long quantitySteps, boolean makerCompleted) {
        return trade(tradeId, quantitySteps, makerCompleted, Instant.parse("2026-07-19T00:00:00Z"));
    }

    private MatchTradeEvent trade(long tradeId,
                                  long quantitySteps,
                                  boolean makerCompleted,
                                  Instant eventTime) {
        return new MatchTradeEvent(
                tradeId, 7001L, "BTC-USDT",
                9001L, 1L, 1001L, OrderSide.BUY,
                9002L, 1L, 2002L,
                500L, 200L, 60_000L, quantitySteps,
                false, makerCompleted, eventTime);
    }
}
