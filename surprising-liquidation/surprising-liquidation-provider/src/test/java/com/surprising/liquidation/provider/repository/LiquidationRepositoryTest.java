package com.surprising.liquidation.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.liquidation.provider.model.LiquidationSizingInput;
import com.surprising.liquidation.api.model.LiquidationOrderStatus;
import com.surprising.risk.api.model.RiskStatus;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class LiquidationRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void latestRiskStatusRequiresFreshSnapshot() {
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(1001L), eq("USDT"), eq(7000L)))
                .thenReturn(List.of(RiskStatus.LIQUIDATION));

        RiskStatus status = repository.latestRiskStatus(1001L, "USDT", Duration.ofSeconds(7));

        assertThat(status).isEqualTo(RiskStatus.LIQUIDATION);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(1001L), eq("USDT"), eq(7000L));
        assertThat(sql.getValue()).contains("event_time >= now() - (? * INTERVAL '1 millisecond')");
    }

    @Test
    void staleOrMissingRiskSnapshotIsTreatedAsNormal() {
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(1001L), eq("USDT"), eq(5000L)))
                .thenReturn(List.of());

        assertThat(repository.latestRiskStatus(1001L, "USDT", Duration.ofSeconds(5)))
                .isEqualTo(RiskStatus.NORMAL);
    }

    @Test
    void lockOpenReduceOnlyStepsFailsOnOverflow() {
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("FROM trading_orders"), anyRowMapper(),
                eq(1001L), eq("BTC-USDT"), eq("CROSS"), eq(7L), eq("SELL")))
                .thenReturn(List.of(Long.MAX_VALUE, 1L));

        assertThatThrownBy(() -> repository.lockOpenReduceOnlySteps(1001L, "BTC-USDT", 7L,
                com.surprising.trading.api.model.OrderSide.SELL))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void markCandidateFailsWhenNoRowIsUpdated() {
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate);
        when(jdbcTemplate.update(contains("UPDATE risk_liquidation_candidates"), eq("COMPLETED"), eq(9401L)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.markCandidate(9401L, "COMPLETED"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("liquidation candidate status update");
    }

    @Test
    void sizingInputCalculatesNotionalWithSharedLongMathAndLongBracketLookup() throws Exception {
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("FROM account_positions p"), anyRowMapper(),
                eq(2002L), eq("BTC-USDT"), eq("CROSS"), eq(7L))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("symbol")).thenReturn("BTC-USDT");
                    when(rs.getLong("version")).thenReturn(7L);
                    when(rs.getString("contract_type")).thenReturn("INVERSE_PERPETUAL");
                    when(rs.getLong("signed_quantity_steps")).thenReturn(10L);
                    when(rs.getLong("mark_price_ticks")).thenReturn(5L);
                    when(rs.getLong("notional_multiplier_units")).thenReturn(100L);
                    when(rs.getLong("price_tick_units")).thenReturn(1L);
                    when(rs.getLong("settle_scale_units")).thenReturn(100L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.queryForObject(contains("FROM instrument_risk_brackets"), eq(Long.class),
                eq("BTC-USDT"), eq(7L), eq(20_000L))).thenReturn(10_000L);

        LiquidationSizingInput input = repository.sizingInput(2002L, "BTC-USDT", 7L, 6L)
                .orElseThrow();

        assertThat(input).isEqualTo(new LiquidationSizingInput(10L, 6L, 20_000L, 2_000L, 10_000L));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(2002L), eq("BTC-USDT"), eq("CROSS"), eq(7L));
        assertThat(sql.getValue())
                .doesNotContain("::NUMERIC")
                .doesNotContain("abs(");
    }

    @Test
    void updateOrderLifecycleFailsWhenCandidateStatusIsNotProcessing() {
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("UPDATE liquidation_orders"), anyRowMapper(),
                eq("FILLED"), eq(7001L))).thenReturn(List.of(9401L));
        when(jdbcTemplate.update(contains("UPDATE risk_liquidation_candidates"),
                eq("COMPLETED"), eq(9401L))).thenReturn(0);

        assertThatThrownBy(() -> repository.updateOrderLifecycle(7001L, LiquidationOrderStatus.FILLED,
                "COMPLETED"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("liquidation candidate lifecycle update");
    }

    @SuppressWarnings("unchecked")
    private <T> RowMapper<T> anyRowMapper() {
        return any(RowMapper.class);
    }
}
