package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.OrderReservationKind;
import com.surprising.account.api.model.OrderReserveAccountCommand;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AccountOrderReservationRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AccountSequenceRepository sequenceRepository = mock(AccountSequenceRepository.class);
    private final AccountOrderReservationRepository repository =
            new AccountOrderReservationRepository(jdbcTemplate, sequenceRepository);

    @Test
    void derivativeReservationPersistsImmutableOrderSnapshotWithoutReadingTradingTables() {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        OrderReserveAccountCommand command = new OrderReserveAccountCommand(
                9001L, "BTC-USDT", OrderSide.BUY, OrderReservationKind.DERIVATIVE_MARGIN,
                AccountType.USDT_PERPETUAL, "USDT", MarginMode.CROSS, PositionSide.NET,
                10L, false, 100L);
        when(jdbcTemplate.update(contains("UPDATE account_balances"), any(Object[].class))).thenReturn(1);
        when(sequenceRepository.nextSequence(AccountSequenceRepository.Sequence.MARGIN_RESERVATION))
                .thenReturn(7001L);
        when(jdbcTemplate.update(contains("INSERT INTO account_margin_reservations"), any(Object[].class)))
                .thenReturn(1);

        assertThat(repository.reserve(ProductLine.LINEAR_PERPETUAL, 1001L, command, now)).isTrue();

        verify(jdbcTemplate).update(contains("INSERT INTO account_margin_reservations"),
                eq(7001L), eq("USDT_PERPETUAL"), eq(1001L), eq("USDT"), eq(9001L), eq("BTC-USDT"),
                eq("CROSS"), eq("NET"), eq(10L), eq(false), eq(100L),
                any(Timestamp.class), any(Timestamp.class));
        verify(jdbcTemplate, never()).query(contains("trading_orders"), anyRowMapper(), any(Object[].class));
    }

    @Test
    void missingExpectedReservationFailsClosedWithoutReadingTradingTables() {
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(), eq(9001L)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(contains("FROM account_spot_order_reservations"), anyRowMapper(), eq(9001L)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(contains("UNION ALL"), anyRowMapper(), eq(9001L), eq(9001L)))
                .thenReturn(List.of());

        assertThatThrownBy(() -> repository.release(
                ProductLine.LINEAR_PERPETUAL, 1001L, 9001L,
                true, 10L, 0L, true, "ORDER_TERMINAL", Instant.parse("2026-07-19T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing account reservation");

        verify(jdbcTemplate, never()).query(contains("trading_orders"), anyRowMapper(), any(Object[].class));
    }

    @Test
    void activeReservationCannotBeReleasedByAnotherUser() throws Exception {
        when(jdbcTemplate.query(contains("FROM account_margin_reservations"), anyRowMapper(), eq(9001L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("account_type")).thenReturn("USDT_PERPETUAL");
                    when(rs.getLong("user_id")).thenReturn(2002L);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getLong("reserved_units")).thenReturn(100L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        assertThatThrownBy(() -> repository.release(
                ProductLine.LINEAR_PERPETUAL, 1001L, 9001L,
                true, 10L, 0L, true, "ORDER_TERMINAL", Instant.parse("2026-07-19T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user mismatch");

        verify(jdbcTemplate, never()).update(contains("UPDATE account_margin_reservations"), any(Object[].class));
    }

    @Test
    void reservationAccountMustMatchCommandProductLine() {
        OrderReserveAccountCommand command = new OrderReserveAccountCommand(
                9001L, "BTC-USDT", OrderSide.BUY, OrderReservationKind.DERIVATIVE_MARGIN,
                AccountType.COIN_PERPETUAL, "BTC", MarginMode.CROSS, PositionSide.NET,
                10L, false, 100L);

        assertThatThrownBy(() -> repository.reserve(
                ProductLine.LINEAR_PERPETUAL, 1001L, command, Instant.parse("2026-07-19T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match product line");

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
