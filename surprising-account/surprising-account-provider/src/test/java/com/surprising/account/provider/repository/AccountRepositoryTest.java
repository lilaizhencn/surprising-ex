package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.trading.api.model.MarginMode;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AccountRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private AccountSequenceRepository sequenceRepository;

    @Test
    void processedTradeIdempotencyIsScopedBySymbol() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.update(contains("INSERT INTO account_processed_trades"), eq(9001L), eq("BTC-USDT")))
                .thenReturn(1);

        repository.markTradeProcessing(9001L, "BTC-USDT");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq(9001L), eq("BTC-USDT"));
        assertThat(sql.getValue()).contains("ON CONFLICT (symbol, trade_id) DO NOTHING");
    }

    @Test
    void duplicateBalanceAdjustmentReturnsCurrentBalanceWhenPayloadMatches() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);

        when(sequenceRepository.nextSequence("ledger-entry")).thenReturn(1L);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(0);
        when(jdbcTemplate.query(contains("FROM account_ledger_entries"), anyRowMapper(),
                eq("deposit-1001-1"), eq(1001L), eq("USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("amount_units")).thenReturn(500L);
                    when(rs.getString("reason")).thenReturn("INITIAL_DEPOSIT");
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.query(contains("SELECT b.user_id"), anyRowMapper(),
                eq(1001L), eq("USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getLong("available_units")).thenReturn(1_000L);
                    when(rs.getLong("locked_units")).thenReturn(0L);
                    when(rs.getLong("equity_units")).thenReturn(1_000L);
                    when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")));
                    return List.of(mapper.mapRow(rs, 0));
                });

        var response = repository.adjustBalance(1001L, "USDT", 500L,
                "deposit-1001-1", "INITIAL_DEPOSIT");

        assertThat(response.availableUnits()).isEqualTo(1_000L);
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
    }

    @Test
    void duplicateBalanceAdjustmentWithDifferentPayloadFailsBeforeBalanceMutation() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);

        when(sequenceRepository.nextSequence("ledger-entry")).thenReturn(1L);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(0);
        when(jdbcTemplate.query(contains("FROM account_ledger_entries"), anyRowMapper(),
                eq("deposit-1001-1"), eq(1001L), eq("USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("amount_units")).thenReturn(500L);
                    when(rs.getString("reason")).thenReturn("INITIAL_DEPOSIT");
                    return List.of(mapper.mapRow(rs, 0));
                });

        assertThatThrownBy(() -> repository.adjustBalance(1001L, "USDT", 600L,
                "deposit-1001-1", "INITIAL_DEPOSIT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conflicting duplicate balance adjustment reference");
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
    }

    @Test
    void realizedLossOnlyDebitsPositionMarginLockedCollateral() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        when(sequenceRepository.nextSequence("ledger-entry")).thenReturn(1L);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_position_margins"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_balances"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_deficits"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("FROM account_position_margins"), anyRowMapper(), eq(1001L), eq("USDT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("symbol")).thenReturn("ETH-USDT");
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getLong("margin_units")).thenReturn(50L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.queryForObject(contains("SELECT b.available_units"), anyRowMapper(),
                eq(1001L), eq("USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("available_units")).thenReturn(20L);
                    when(rs.getLong("locked_units")).thenReturn(100L);
                    when(rs.getLong("deficit_units")).thenReturn(0L);
                    return mapper.mapRow(rs, 0);
                });

        repository.settleRealizedPnl(1001L, "USDT", 5001L, 9001L, -90L, now);

        verify(jdbcTemplate).update(contains("UPDATE account_position_margins"),
                eq(50L), any(Timestamp.class), eq(1001L), eq("ETH-USDT"), eq("USDT"), eq("CROSS"), eq(50L));
        verify(jdbcTemplate).update(contains("UPDATE account_balances"),
                eq(0L), eq(50L), any(Timestamp.class), eq(1001L), eq("USDT"));
        verify(jdbcTemplate).update(contains("UPDATE account_deficits"),
                eq(20L), any(Timestamp.class), eq(1001L), eq("USDT"));
    }

    @Test
    void tradePnlLedgerConflictFailsBeforeBalanceMutation() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        when(sequenceRepository.nextSequence("ledger-entry")).thenReturn(1L);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.settleRealizedPnl(1001L, "USDT", 5001L, 9001L, 25L, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trade pnl ledger insert");
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_deficits"), any(Object[].class));
    }

    @Test
    void tradeFeeLedgerConflictFailsBeforeBalanceMutation() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        when(sequenceRepository.nextSequence("ledger-entry")).thenReturn(1L);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.settleTradeFee(1001L, "USDT", 5001L, 9001L, -25L,
                "TAKER_FEE", 500L, "BTC-USDT", now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trade fee ledger insert");
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_deficits"), any(Object[].class));
    }

    @Test
    void orderFeeSnapshotIsReadFromAcceptedOrder() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.query(contains("FROM trading_orders"), anyRowMapper(),
                eq(5001L), eq(1001L), eq("BTC-USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("maker_fee_rate_ppm")).thenReturn(-100L);
                    when(rs.getLong("taker_fee_rate_ppm")).thenReturn(400L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        var snapshot = repository.orderFeeSnapshot(5001L, 1001L, "BTC-USDT");

        assertThat(snapshot.makerFeeRatePpm()).isEqualTo(-100L);
        assertThat(snapshot.takerFeeRatePpm()).isEqualTo(400L);
    }

    @Test
    void tradeFeeLedgerStoresFeeAuditSnapshot() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        when(sequenceRepository.nextSequence("ledger-entry")).thenReturn(1L);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_balances"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_deficits"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("FROM account_position_margins"), anyRowMapper(), eq(1001L), eq("USDT")))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(contains("SELECT b.available_units"), anyRowMapper(),
                eq(1001L), eq("USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("available_units")).thenReturn(100L);
                    when(rs.getLong("locked_units")).thenReturn(0L);
                    when(rs.getLong("deficit_units")).thenReturn(0L);
                    return mapper.mapRow(rs, 0);
                });

        repository.settleTradeFee(1001L, "USDT", 5001L, 9001L, -25L,
                "TAKER_FEE", 500L, "BTC-USDT", now);

        verify(jdbcTemplate).update(contains("INSERT INTO account_ledger_entries"),
                eq(1L), eq(1001L), eq("USDT"), eq(-25L), eq("9001:5001"), eq("TAKER_FEE"),
                eq(9001L), eq(5001L), eq("BTC-USDT"), eq(500L), any(Timestamp.class));
        verify(jdbcTemplate).update(contains("UPDATE account_ledger_entries"),
                eq(75L), eq("9001:5001"), eq(1001L), eq("USDT"));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_position_margins"),
                any(Object[].class));
    }

    @Test
    void openingFillFailsWhenOrderMarginReservationIsMissing() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USDT"))).thenReturn(List.of());

        assertThatThrownBy(() -> repository.consumeOrderMargin(9001L, 1001L, "BTC-USDT",
                MarginMode.CROSS, 3L, 30L, false, Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing order margin reservation");
    }

    @Test
    void openingFillFailsWhenOrderMarginConsumptionUpdateIsSkipped() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getLong("reserved_units")).thenReturn(100L);
                    when(rs.getLong("released_units")).thenReturn(0L);
                    when(rs.getLong("position_margin_units")).thenReturn(0L);
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getLong("quantity_steps")).thenReturn(10L);
                    when(rs.getBoolean("reduce_only")).thenReturn(false);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.update(contains("UPDATE account_margin_reservations"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.consumeOrderMargin(9001L, 1001L, "BTC-USDT",
                MarginMode.CROSS, 3L, 30L, false, Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order margin consumption");
    }

    @Test
    void openingFillMovesActualMarginAndReleasesPriceImprovementExcess() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getLong("reserved_units")).thenReturn(660L);
                    when(rs.getLong("released_units")).thenReturn(0L);
                    when(rs.getLong("position_margin_units")).thenReturn(0L);
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getLong("quantity_steps")).thenReturn(6L);
                    when(rs.getBoolean("reduce_only")).thenReturn(false);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.update(contains("UPDATE account_margin_reservations"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO account_position_margins"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_balances"), any(Object[].class)))
                .thenReturn(1);

        repository.consumeOrderMargin(9001L, 1001L, "BTC-USDT", MarginMode.CROSS, 6L, 600L, true, now);

        verify(jdbcTemplate).update(contains("INSERT INTO account_position_margins"),
                eq(1001L), eq("BTC-USDT"), eq("USDT"), eq("CROSS"), eq(600L), any(Timestamp.class));
        verify(jdbcTemplate).update(contains("UPDATE account_balances"),
                eq(60L), eq(60L), any(Timestamp.class), eq(1001L), eq("USDT"), eq(60L));
        verify(jdbcTemplate).update(contains("UPDATE account_margin_reservations"),
                eq(60L), eq(60L), eq(60L), eq("ORDER_PRICE_IMPROVEMENT"), any(Timestamp.class), eq(9001L));
    }

    @Test
    void closingFillFailsWhenNonReduceOnlyOrderMarginReservationIsMissing() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USDT"))).thenReturn(List.of());
        when(jdbcTemplate.query(contains("SELECT reduce_only"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getBoolean("reduce_only")).thenReturn(false);
                    return List.of(mapper.mapRow(rs, 0));
                });

        assertThatThrownBy(() -> repository.releaseOrderMargin(9001L, 1001L, "BTC-USDT",
                3L, false, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing order margin reservation for closing fill 9001");
        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
    }

    @Test
    void closingFillAllowsMissingOrderMarginReservationForReduceOnlyOrder() throws Exception {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USDT"))).thenReturn(List.of());
        when(jdbcTemplate.query(contains("SELECT reduce_only"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getBoolean("reduce_only")).thenReturn(true);
                    return List.of(mapper.mapRow(rs, 0));
                });

        repository.releaseOrderMargin(9001L, 1001L, "BTC-USDT", 3L, true, now);

        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_margin_reservations"), any(Object[].class));
    }

    @Test
    void closingFillFailsWhenOrderRowIsMissingForMarginRelease() {
        AccountRepository repository = new AccountRepository(jdbcTemplate, sequenceRepository);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USDT"))).thenReturn(List.of());
        when(jdbcTemplate.query(contains("SELECT reduce_only"), anyRowMapper(),
                eq(9001L), eq(1001L), eq("BTC-USDT"))).thenReturn(List.of());

        assertThatThrownBy(() -> repository.releaseOrderMargin(9001L, 1001L, "BTC-USDT",
                3L, false, now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("order not found for account margin release 9001");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
