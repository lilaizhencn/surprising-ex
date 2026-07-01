package com.surprising.funding.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingRateInput;
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
class FundingRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void saveRateFailsWhenInsertIsSkipped() {
        FundingRepository repository = new FundingRepository(jdbcTemplate);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        FundingRateInput input = new FundingRateInput("BTC-USDT", 0L, 100L, 10L,
                -3_750L, 3_750L, 8, now);
        when(jdbcTemplate.update(contains("INSERT INTO funding_rate_ticks"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.saveRate(input, 11L, 110L,
                Instant.parse("2026-07-01T08:00:00Z"), now))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("funding rate insert");
    }

    @Test
    void paymentCandidatesConvertMarkTicksWithPositionInstrumentVersion() {
        FundingRepository repository = new FundingRepository(jdbcTemplate);
        Instant fundingTime = Instant.parse("2026-07-01T08:00:00Z");

        repository.paymentCandidates(new FundingRateResponse("BTC-USDT", 11L, 100L,
                90L, 10L, fundingTime, 8, "PREDICTED", fundingTime.minusSeconds(10)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq("BTC-USDT"),
                any(Timestamp.class), eq("BTC-USDT"));

        assertThat(sql.getValue())
                .contains("JOIN instruments i ON i.symbol = p.symbol AND i.version = p.instrument_version")
                .contains("m.mark_price_units")
                .contains("FROM price_mark_ticks m")
                .contains("WHERE m.symbol = p.symbol")
                .doesNotContain("::NUMERIC")
                .doesNotContain("instrument_current_versions");
    }

    @Test
    void paymentCandidatesCalculateNotionalWithSharedLongMath() throws Exception {
        FundingRepository repository = new FundingRepository(jdbcTemplate);
        Instant fundingTime = Instant.parse("2026-07-01T08:00:00Z");
        when(jdbcTemplate.query(contains("WITH rate_row"), anyRowMapper(), eq("BTC-USDT"),
                any(Timestamp.class), eq("BTC-USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("symbol")).thenReturn("BTC-USDT");
                    when(rs.getString("asset")).thenReturn("BTC");
                    when(rs.getString("contract_type")).thenReturn("INVERSE_PERPETUAL");
                    when(rs.getLong("signed_quantity_steps")).thenReturn(-10L);
                    when(rs.getLong("mark_price_ticks")).thenReturn(5L);
                    when(rs.getLong("notional_multiplier_units")).thenReturn(100L);
                    when(rs.getLong("price_tick_units")).thenReturn(1L);
                    when(rs.getLong("settle_scale_units")).thenReturn(100L);
                    when(rs.getLong("funding_rate_ppm")).thenReturn(100_000L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<FundingPaymentCandidate> candidates = repository.paymentCandidates(new FundingRateResponse(
                "BTC-USDT", 11L, 100L, 90L, 10L, fundingTime, 8, "PREDICTED",
                fundingTime.minusSeconds(10)));

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.notionalUnits()).isEqualTo(20_000L);
            assertThat(candidate.amountUnits()).isEqualTo(2_000L);
        });
    }

    @Test
    void fundingChargeOnlyDebitsPositionMarginLockedCollateral() throws Exception {
        FundingRepository repository = new FundingRepository(jdbcTemplate);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        FundingPaymentCandidate payment = new FundingPaymentCandidate(
                1001L, "BTC-USDT", "USDT", 10L, 1_000L, 90_000L, -90L);

        when(jdbcTemplate.queryForObject(contains("INSERT INTO account_sequences"), eq(Long.class),
                eq("ledger-entry"))).thenReturn(1L);
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

        repository.applyPaymentToAccount(3001L, payment, now);

        verify(jdbcTemplate).update(contains("UPDATE account_position_margins"),
                eq(50L), any(Timestamp.class), eq(1001L), eq("ETH-USDT"), eq("USDT"), eq(50L));
        verify(jdbcTemplate).update(contains("UPDATE account_balances"),
                eq(0L), eq(50L), any(Timestamp.class), eq(1001L), eq("USDT"));
        verify(jdbcTemplate).update(contains("UPDATE account_deficits"),
                eq(20L), any(Timestamp.class), eq(1001L), eq("USDT"));
    }

    @Test
    void applyPaymentToAccountFailsWhenLedgerInsertIsSkipped() {
        FundingRepository repository = new FundingRepository(jdbcTemplate);
        FundingPaymentCandidate payment = new FundingPaymentCandidate(
                1001L, "BTC-USDT", "USDT", 10L, 1_000L, 90_000L, -90L);
        when(jdbcTemplate.queryForObject(contains("INSERT INTO account_sequences"), eq(Long.class),
                eq("ledger-entry"))).thenReturn(1L);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.applyPaymentToAccount(3001L, payment,
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("funding account ledger insert");
    }

    @Test
    void applyPaymentToAccountFailsWhenBalanceUpdateIsSkipped() throws Exception {
        FundingRepository repository = new FundingRepository(jdbcTemplate);
        FundingPaymentCandidate payment = new FundingPaymentCandidate(
                1001L, "BTC-USDT", "USDT", -10L, 1_000L, 90_000L, 90L);
        when(jdbcTemplate.queryForObject(contains("INSERT INTO account_sequences"), eq(Long.class),
                eq("ledger-entry"))).thenReturn(1L);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("SELECT b.available_units"), anyRowMapper(),
                eq(1001L), eq("USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("available_units")).thenReturn(20L);
                    when(rs.getLong("locked_units")).thenReturn(100L);
                    when(rs.getLong("deficit_units")).thenReturn(0L);
                    return mapper.mapRow(rs, 0);
                });
        when(jdbcTemplate.update(contains("UPDATE account_balances"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.applyPaymentToAccount(3001L, payment,
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("funding account balance update");
    }

    @Test
    void completeSettlementFailsWhenNoRowIsUpdated() {
        FundingRepository repository = new FundingRepository(jdbcTemplate);
        when(jdbcTemplate.update(contains("UPDATE funding_settlements"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.completeSettlement(3001L, -90L, 90L, 2,
                Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("funding settlement completion");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
