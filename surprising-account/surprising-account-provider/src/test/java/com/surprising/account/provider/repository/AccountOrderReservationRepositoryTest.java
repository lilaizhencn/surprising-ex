package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AccountOrderReservationRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AccountSequenceRepository sequenceRepository = mock(AccountSequenceRepository.class);
    private final AccountOrderReservationRepository repository =
            new AccountOrderReservationRepository(jdbcTemplate, sequenceRepository);

    @Test
    void derivativeReservationOnlyMovesAvailableBalanceToLocked() {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        when(jdbcTemplate.update(contains("UPDATE account_balances"), any(Object[].class))).thenReturn(1);

        assertThat(repository.reserve(ProductLine.LINEAR_PERPETUAL, 1001L, derivativeCommand(), now)).isTrue();

        verify(jdbcTemplate).update(contains("UPDATE account_balances"),
                eq(100L), eq(100L), any(Timestamp.class), eq(1001L), eq("USDT"), eq(100L));
        verify(jdbcTemplate, never()).update(contains("account_margin_reservations"), any(Object[].class));
    }

    @Test
    void terminalReleaseUsesSettlementAuditAndCommandResults() throws Exception {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        when(jdbcTemplate.query(contains("FROM account_trade_settlement_sides"), anyRowMapper(), eq(9001L)))
                .thenAnswer(invocation -> usage(invocation.getArgument(1), 30L, 10L));
        when(jdbcTemplate.queryForObject(contains("FROM account_commands"), eq(Long.class), eq("9001")))
                .thenReturn(5L);
        when(jdbcTemplate.update(contains("UPDATE account_balances"), any(Object[].class))).thenReturn(1);

        long released = repository.release(ProductLine.LINEAR_PERPETUAL, 1001L, 9001L,
                true, 10L, 0L, true, AccountType.USDT_PERPETUAL, "USDT", 100L,
                "ORDER_TERMINAL", now);

        assertThat(released).isEqualTo(55L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), eq(Long.class), eq("9001"));
        assertThat(sql.getValue()).contains("result_payload ->> 'releasedUnits' IS NOT NULL")
                .doesNotContain("result_payload ?");
        verify(jdbcTemplate).update(contains("UPDATE account_balances"),
                eq(55L), eq(55L), any(Timestamp.class), eq(1001L), eq("USDT"), eq(55L));
    }

    @Test
    void expectedDerivativeReleaseRequiresImmutableSnapshot() {
        assertThatThrownBy(() -> repository.release(ProductLine.LINEAR_PERPETUAL, 1001L, 9001L,
                true, 10L, 0L, true, null, null, 0L,
                "ORDER_TERMINAL", Instant.parse("2026-07-19T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing derivative reservation snapshot");
    }

    @Test
    void reservationAccountMustMatchCommandProductLine() {
        OrderReserveAccountCommand command = new OrderReserveAccountCommand(
                9001L, "BTC-USDT", OrderSide.BUY, OrderReservationKind.DERIVATIVE_MARGIN,
                AccountType.COIN_PERPETUAL, "BTC", MarginMode.CROSS, PositionSide.NET,
                10L, false, 100L);

        assertThatThrownBy(() -> repository.reserve(ProductLine.LINEAR_PERPETUAL, 1001L, command,
                Instant.parse("2026-07-19T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match product line");
    }

    private OrderReserveAccountCommand derivativeCommand() {
        return new OrderReserveAccountCommand(
                9001L, "BTC-USDT", OrderSide.BUY, OrderReservationKind.DERIVATIVE_MARGIN,
                AccountType.USDT_PERPETUAL, "USDT", MarginMode.CROSS, PositionSide.NET,
                10L, false, 100L);
    }

    private List<Object> usage(RowMapper<?> mapper, long consumed, long released) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("consumed_units")).thenReturn(consumed);
        when(rs.getLong("released_units")).thenReturn(released);
        return List.of(mapper.mapRow(rs, 0));
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
