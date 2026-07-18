package com.surprising.adl.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.adl.api.model.AdlSide;
import com.surprising.adl.provider.config.AdlProperties;
import com.surprising.adl.provider.model.AdlCandidate;
import com.surprising.adl.provider.model.DeficitRow;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
class AdlRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void claimResidualDeficitsUsesProductDeficitsWhenProductTopicsEnabled() throws Exception {
        when(jdbcTemplate.query(contains("FROM account_product_deficits"), anyRowMapper(),
                eq("USDT_DELIVERY"), eq(10_000L), eq(3))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("account_type")).thenReturn("USDT_DELIVERY");
                    when(rs.getLong("user_id")).thenReturn(2002L);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getLong("deficit_units")).thenReturn(500L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        AdlRepository repository = new AdlRepository(jdbcTemplate, productProperties(ProductLine.LINEAR_DELIVERY));

        List<DeficitRow> deficits = repository.claimResidualDeficits(3, Duration.ofSeconds(10));

        assertThat(deficits).singleElement().satisfies(deficit -> {
            assertThat(deficit.accountType()).isEqualTo("USDT_DELIVERY");
            assertThat(deficit.userId()).isEqualTo(2002L);
            assertThat(deficit.asset()).isEqualTo("USDT");
            assertThat(deficit.deficitUnits()).isEqualTo(500L);
        });
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq("USDT_DELIVERY"), eq(10_000L), eq(3));
        assertThat(sql.getValue())
                .contains("FROM account_product_deficits")
                .contains("f.account_type = d.account_type")
                .contains("d.account_type = ?");
    }

    @Test
    void queueFiltersCandidatesByConfiguredProductLine() throws Exception {
        when(jdbcTemplate.query(contains("FROM ("), anyRowMapper(), eq("BTC-USDT-260925"), eq(4L), eq(110L),
                eq("USDT_DELIVERY"), eq("USDT"), eq("LINEAR_DELIVERY"), eq(0L), eq(0L), eq(5)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getString("symbol")).thenReturn("BTC-USDT-260925");
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getString("position_side")).thenReturn("LONG");
                    when(rs.getString("contract_type")).thenReturn("LINEAR_DELIVERY");
                    when(rs.getLong("signed_quantity_steps")).thenReturn(10L);
                    when(rs.getLong("entry_price_ticks")).thenReturn(100L);
                    when(rs.getLong("mark_price_ticks")).thenReturn(110L);
                    when(rs.getLong("notional_multiplier_units")).thenReturn(100L);
                    when(rs.getLong("price_tick_units")).thenReturn(1L);
                    when(rs.getLong("settle_scale_units")).thenReturn(100_000_000L);
                    when(rs.getLong("margin_units")).thenReturn(1_000L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        AdlRepository repository = repositoryWithMarkPrice(productProperties(ProductLine.LINEAR_DELIVERY),
                "BTC-USDT-260925", 4L, 110L);

        List<AdlCandidate> queue = repository.queue("USDT", 1, Duration.ofSeconds(5));

        assertThat(queue).singleElement().satisfies(candidate ->
                assertThat(candidate.symbol()).isEqualTo("BTC-USDT-260925"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq("BTC-USDT-260925"), eq(4L), eq(110L),
                eq("USDT_DELIVERY"), eq("USDT"), eq("LINEAR_DELIVERY"), eq(0L), eq(0L), eq(5));
        assertThat(sql.getValue())
                .contains("mark_prices(symbol, instrument_version, mark_price_ticks)")
                .doesNotContain("price_mark_ticks")
                .contains("account_product_deficits")
                .contains("d.account_type = ?")
                .contains("p.product_line = ?")
                .contains("m.product_line = p.product_line");
    }

    @Test
    void queueCalculatesProfitAndNotionalWithSharedLongMath() throws Exception {
        when(jdbcTemplate.query(contains("FROM ("), anyRowMapper(), eq("BTC-USDT"), eq(7L), eq(110L),
                eq("USDT"), eq(0L), eq(0L), eq(5))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getString("symbol")).thenReturn("BTC-USDT");
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getString("position_side")).thenReturn("LONG");
                    when(rs.getString("contract_type")).thenReturn("LINEAR_PERPETUAL");
                    when(rs.getLong("signed_quantity_steps")).thenReturn(10L);
                    when(rs.getLong("entry_price_ticks")).thenReturn(100L);
                    when(rs.getLong("mark_price_ticks")).thenReturn(110L);
                    when(rs.getLong("notional_multiplier_units")).thenReturn(100L);
                    when(rs.getLong("price_tick_units")).thenReturn(1L);
                    when(rs.getLong("settle_scale_units")).thenReturn(100_000_000L);
                    when(rs.getLong("margin_units")).thenReturn(1_000L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        AdlRepository repository = repositoryWithMarkPrice(new AdlProperties(), "BTC-USDT", 7L, 110L);

        List<AdlCandidate> queue = repository.queue("USDT", 1, Duration.ofSeconds(5));

        assertThat(queue).singleElement().satisfies(candidate -> {
            assertThat(candidate.marginMode()).isEqualTo(MarginMode.CROSS);
            assertThat(candidate.positionSide()).isEqualTo(PositionSide.LONG);
            assertThat(candidate.absQuantitySteps()).isEqualTo(10L);
            assertThat(candidate.profitTicksPerStep()).isEqualTo(10L);
            assertThat(candidate.notionalUnits()).isEqualTo(110_000L);
            assertThat(candidate.unrealizedProfitUnits()).isEqualTo(10_000L);
            assertThat(candidate.profitRatePpm()).isEqualTo(90_909L);
            assertThat(candidate.effectiveLeveragePpm()).isEqualTo(110_000_000L);
        });
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq("BTC-USDT"), eq(7L), eq(110L),
                eq("USDT"), eq(0L), eq(0L), eq(5));
        assertThat(sql.getValue())
                .doesNotContain("price_mark_ticks")
                .doesNotContain("abs(")
                .doesNotContain("profit_ticks_per_step");
    }

    @Test
    void executeAdlUsesProductAccountTablesAndLedgerWhenProductTopicsEnabled() throws Exception {
        when(jdbcTemplate.query(contains("SELECT balance_units"), anyRowMapper(), eq("USDT_DELIVERY"), eq("USDT")))
                .thenReturn(List.of(0L));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO adl_sequences"), eq(Long.class),
                eq("adl-event"))).thenReturn(301L);
        when(jdbcTemplate.queryForObject(contains("SELECT nextval"), eq(Long.class),
                eq("public.account_product_ledger_entry_seq"))).thenReturn(1L);
        when(jdbcTemplate.update(contains("UPDATE account_positions"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE trading_symbol_open_interest"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("FROM account_position_margins"), anyRowMapper(),
                eq(1001L), eq("BTC-USDT-260925"), eq("USDT"), eq("CROSS"), eq("LONG"),
                eq("LINEAR_DELIVERY"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getString("position_side")).thenReturn("LONG");
                    when(rs.getLong("margin_units")).thenReturn(100L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.update(contains("UPDATE account_product_balances"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_position_margins"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("SELECT b.available_units"), anyRowMapper(),
                eq("USDT_DELIVERY"), eq(1001L), eq("USDT")))
                .thenAnswer(invocation -> balanceState(invocation, 50L, 50L, 0L))
                .thenAnswer(invocation -> balanceState(invocation, 550L, 50L, 0L));
        when(jdbcTemplate.update(contains("UPDATE account_product_deficits"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO account_product_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO adl_events"), any(Object[].class)))
                .thenReturn(1);
        AdlRepository repository = new AdlRepository(jdbcTemplate, productProperties(ProductLine.LINEAR_DELIVERY));

        long remaining = repository.executeAdl(
                new DeficitRow("USDT_DELIVERY", 2002L, "USDT", 500L),
                new AdlCandidate(1001L, "USDT", "BTC-USDT-260925", MarginMode.CROSS, PositionSide.LONG,
                        AdlSide.LONG,
                        10L, 10L, 100L, 200L, 100L,
                        2_000L, 1_000L, 100L,
                        500_000L, 20_000_000L, 10_000_000L),
                500L);

        assertThat(remaining).isZero();
        verify(jdbcTemplate).update(contains("UPDATE account_positions"),
                eq(5L), eq(5L), eq(100L), eq(5L), eq(5L), eq(10L), eq(500L), any(Timestamp.class), eq(1001L),
                eq("BTC-USDT-260925"), eq("CROSS"), eq("LONG"), eq("LINEAR_DELIVERY"));
        verify(jdbcTemplate).update(contains("INSERT INTO trading_symbol_open_interest"),
                eq("LINEAR_DELIVERY"), eq("BTC-USDT-260925"), any(Timestamp.class));
        verify(jdbcTemplate).update(contains("UPDATE trading_symbol_open_interest"),
                eq(-5L), eq(0L), eq(-5L), eq(0L), any(Timestamp.class), eq("BTC-USDT-260925"),
                eq("LINEAR_DELIVERY"), eq(-5L), eq(0L));
        verify(jdbcTemplate).update(contains("locked_units = locked_units - ?"),
                eq(50L), eq(50L), any(Timestamp.class), eq("USDT_DELIVERY"), eq(1001L), eq("USDT"), eq(50L));
        verify(jdbcTemplate).update(contains("UPDATE account_position_margins"),
                eq(50L), any(Timestamp.class), eq(1001L), eq("BTC-USDT-260925"), eq("USDT"),
                eq("CROSS"), eq("LONG"), eq(50L), eq("LINEAR_DELIVERY"));
        verify(jdbcTemplate, times(2)).queryForObject(contains("USING (account_type, user_id, asset)"), anyRowMapper(),
                eq("USDT_DELIVERY"), eq(1001L), eq("USDT"));
        verify(jdbcTemplate).update(contains("UPDATE account_product_balances"),
                eq(550L), eq(50L), any(Timestamp.class), eq("USDT_DELIVERY"), eq(1001L), eq("USDT"));
        verify(jdbcTemplate).update(contains("UPDATE account_product_deficits"),
                eq(0L), any(Timestamp.class), eq("USDT_DELIVERY"), eq(2002L), eq("USDT"));
        verify(jdbcTemplate).update(contains("INSERT INTO account_product_ledger_entries"),
                eq(1L), eq("USDT_DELIVERY"), eq(1001L), eq("USDT"), eq(500L), eq(600L),
                eq("ADL_REALIZED_PNL"), eq("301"), eq("ADL_POSITION_DELEVERAGED"), any(Timestamp.class));
        verify(jdbcTemplate).update(contains("INSERT INTO adl_events"),
                eq(301L), eq("USDT_DELIVERY"), eq(2002L), eq(1001L), eq("USDT"), eq("BTC-USDT-260925"),
                eq("LONG"), eq("LONG"), eq(5L), eq(100L), eq(200L), eq(500L), eq(500L), eq(500L), eq(0L),
                eq(10_000_000L), any(Timestamp.class));
    }

    @Test
    void productLineModeRejectsDeficitFromAnotherAccountTypeBeforeFundsMove() {
        AdlRepository repository = new AdlRepository(jdbcTemplate, productProperties(ProductLine.LINEAR_DELIVERY));

        assertThatThrownBy(() -> repository.executeAdl(
                new DeficitRow("COIN_DELIVERY", 2002L, "USDT", 500L),
                new AdlCandidate(1001L, "USDT", "BTC-USDT-260925", MarginMode.CROSS, PositionSide.LONG,
                        AdlSide.LONG,
                        10L, 10L, 100L, 200L, 100L,
                        2_000L, 1_000L, 100L,
                        500_000L, 20_000_000L, 10_000_000L),
                500L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match provider account type USDT_DELIVERY");

        verify(jdbcTemplate, never()).update(contains("UPDATE account_positions"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("UPDATE account_product_balances"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_product_ledger_entries"),
                any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO adl_events"), any(Object[].class));
    }

    @Test
    void executeAdlLocksInsuranceFundAndSkipsWhenFundHasBalance() {
        when(jdbcTemplate.query(contains("SELECT balance_units"), anyRowMapper(), eq("USDT_PERPETUAL"), eq("USDT")))
                .thenReturn(List.of(1L));
        AdlRepository repository = new AdlRepository(jdbcTemplate);

        long remaining = repository.executeAdl(
                new DeficitRow(2002L, "USDT", 500L),
                new AdlCandidate(1001L, "USDT", "BTC-USDT", AdlSide.LONG,
                        10L, 10L, 50_000L, 60_000L, 10_000L,
                        600_000L, 1_000L, 100_000L,
                        1_666L, 6_000_000L, 9_996L),
                500L);

        assertThat(remaining).isEqualTo(500L);
        verify(jdbcTemplate).update(contains("INSERT INTO insurance_fund_balances"),
                eq("USDT_PERPETUAL"), eq("USDT"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq("USDT_PERPETUAL"), eq("USDT"));
        assertThat(sql.getValue()).contains("FOR UPDATE");
        verify(jdbcTemplate, never()).update(contains("UPDATE account_positions"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO adl_events"), any(Object[].class));
    }

    @Test
    void executeAdlReducesTargetPositionTransfersProfitAndClearsDeficit() throws Exception {
        when(jdbcTemplate.query(contains("SELECT balance_units"), anyRowMapper(), eq("USDT_PERPETUAL"), eq("USDT")))
                .thenReturn(List.of(0L));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO adl_sequences"), eq(Long.class),
                eq("adl-event"))).thenReturn(301L);
        when(jdbcTemplate.queryForObject(contains("SELECT nextval"), eq(Long.class),
                eq("public.account_ledger_entry_seq"))).thenReturn(1L);
        when(jdbcTemplate.update(contains("UPDATE account_positions"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE trading_symbol_open_interest"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("FROM account_position_margins"), anyRowMapper(),
                eq(1001L), eq("BTC-USDT"), eq("USDT"), eq("CROSS"), eq("LONG"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getString("position_side")).thenReturn("LONG");
                    when(rs.getLong("margin_units")).thenReturn(100L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.update(contains("UPDATE account_balances"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_position_margins"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.queryForObject(contains("SELECT b.available_units"), anyRowMapper(),
                eq(1001L), eq("USDT")))
                .thenAnswer(invocation -> balanceState(invocation, 50L, 50L, 0L))
                .thenAnswer(invocation -> balanceState(invocation, 550L, 50L, 0L));
        when(jdbcTemplate.update(contains("UPDATE account_deficits"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO account_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("INSERT INTO adl_events"), any(Object[].class)))
                .thenReturn(1);
        AdlRepository repository = new AdlRepository(jdbcTemplate);

        long remaining = repository.executeAdl(
                new DeficitRow(2002L, "USDT", 500L),
                new AdlCandidate(1001L, "USDT", "BTC-USDT", MarginMode.CROSS, PositionSide.LONG,
                        AdlSide.LONG,
                        10L, 10L, 100L, 200L, 100L,
                        2_000L, 1_000L, 100L,
                        500_000L, 20_000_000L, 10_000_000L),
                500L);

        assertThat(remaining).isZero();
        verify(jdbcTemplate).update(contains("UPDATE account_positions"),
                eq(5L), eq(5L), eq(100L), eq(5L), eq(5L), eq(10L), eq(500L), any(Timestamp.class),
                eq(1001L), eq("BTC-USDT"), eq("CROSS"), eq("LONG"));
        verify(jdbcTemplate).update(contains("INSERT INTO trading_symbol_open_interest"),
                eq("LINEAR_PERPETUAL"), eq("BTC-USDT"), any(Timestamp.class));
        verify(jdbcTemplate).update(contains("UPDATE trading_symbol_open_interest"),
                eq(-5L), eq(0L), eq(-5L), eq(0L), any(Timestamp.class), eq("BTC-USDT"),
                eq("LINEAR_PERPETUAL"), eq(-5L), eq(0L));
        verify(jdbcTemplate).update(contains("locked_units = locked_units - ?"),
                eq(50L), eq(50L), any(Timestamp.class), eq(1001L), eq("USDT"), eq(50L));
        verify(jdbcTemplate).update(contains("UPDATE account_position_margins"),
                eq(50L), any(Timestamp.class), eq(1001L), eq("BTC-USDT"), eq("USDT"), eq("CROSS"), eq("LONG"),
                eq(50L));
        verify(jdbcTemplate).update(contains("UPDATE account_balances"),
                eq(550L), eq(50L), any(Timestamp.class), eq(1001L), eq("USDT"));
        verify(jdbcTemplate).update(contains("UPDATE account_balances"),
                eq(50L), eq(50L), any(Timestamp.class), eq(1001L), eq("USDT"));
        verify(jdbcTemplate).update(contains("UPDATE account_deficits"),
                eq(0L), any(Timestamp.class), eq(2002L), eq("USDT"));
        verify(jdbcTemplate).update(contains("INSERT INTO account_ledger_entries"),
                eq(1L), eq(1001L), eq("USDT"), eq(500L), eq(600L),
                eq("ADL_REALIZED_PNL"), eq("301"), eq("ADL_POSITION_DELEVERAGED"),
                any(Timestamp.class));
        verify(jdbcTemplate).update(contains("INSERT INTO account_ledger_entries"),
                eq(2L), eq(1001L), eq("USDT"), eq(-500L), eq(100L),
                eq("ADL_TRANSFER"), eq("301"), eq("ADL_DEFICIT_TRANSFER"),
                any(Timestamp.class));
        verify(jdbcTemplate).update(contains("INSERT INTO account_ledger_entries"),
                eq(3L), eq(2002L), eq("USDT"), eq(500L), eq(0L),
                eq("ADL_COVERAGE"), eq("301"), eq("ADL_DEFICIT_COVERAGE"),
                any(Timestamp.class));
        verify(jdbcTemplate).update(contains("INSERT INTO adl_events"),
                eq(301L), eq("USDT_PERPETUAL"), eq(2002L), eq(1001L), eq("USDT"), eq("BTC-USDT"), eq("LONG"),
                eq("LONG"),
                eq(5L), eq(100L), eq(200L), eq(500L), eq(500L), eq(500L), eq(0L),
                eq(10_000_000L), any(Timestamp.class));
    }

    @Test
    void executeAdlFailsWhenTargetPositionUpdateIsSkipped() {
        when(jdbcTemplate.query(contains("SELECT balance_units"), anyRowMapper(), eq("USDT_PERPETUAL"), eq("USDT")))
                .thenReturn(List.of(0L));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO adl_sequences"), eq(Long.class),
                eq("adl-event"))).thenReturn(301L);
        when(jdbcTemplate.update(contains("UPDATE account_positions"), any(Object[].class)))
                .thenReturn(0);
        AdlRepository repository = new AdlRepository(jdbcTemplate);

        assertThatThrownBy(() -> repository.executeAdl(
                new DeficitRow(2002L, "USDT", 500L),
                new AdlCandidate(1001L, "USDT", "BTC-USDT", AdlSide.LONG,
                        10L, 10L, 50_000L, 60_000L, 10_000L,
                        600_000L, 1_000L, 100_000L,
                        1_666L, 6_000_000L, 9_996L),
                500L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADL target position");

        verify(jdbcTemplate, never()).update(contains("UPDATE account_balances"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_ledger_entries"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO adl_events"), any(Object[].class));
    }

    @Test
    void executeAdlFailsWhenOpenInterestUpdateIsSkipped() {
        when(jdbcTemplate.query(contains("SELECT balance_units"), anyRowMapper(), eq("USDT_PERPETUAL"), eq("USDT")))
                .thenReturn(List.of(0L));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO adl_sequences"), eq(Long.class),
                eq("adl-event"))).thenReturn(301L);
        when(jdbcTemplate.update(contains("UPDATE account_positions"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE trading_symbol_open_interest"), any(Object[].class)))
                .thenReturn(0);
        AdlRepository repository = new AdlRepository(jdbcTemplate);

        assertThatThrownBy(() -> repository.executeAdl(
                new DeficitRow(2002L, "USDT", 500L),
                new AdlCandidate(1001L, "USDT", "BTC-USDT", AdlSide.LONG,
                        10L, 10L, 50_000L, 60_000L, 10_000L,
                        600_000L, 1_000L, 100_000L,
                        1_666L, 6_000_000L, 9_996L),
                500L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADL symbol open interest update");

        verify(jdbcTemplate, never()).query(contains("FROM account_position_margins"), anyRowMapper(),
                any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_ledger_entries"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO adl_events"), any(Object[].class));
    }

    @Test
    void executeAdlFailsWhenTargetMarginReleaseIsSkipped() throws Exception {
        when(jdbcTemplate.query(contains("SELECT balance_units"), anyRowMapper(), eq("USDT_PERPETUAL"), eq("USDT")))
                .thenReturn(List.of(0L));
        when(jdbcTemplate.queryForObject(contains("INSERT INTO adl_sequences"), eq(Long.class),
                eq("adl-event"))).thenReturn(301L);
        when(jdbcTemplate.update(contains("UPDATE account_positions"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE trading_symbol_open_interest"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("FROM account_position_margins"), anyRowMapper(),
                eq(1001L), eq("BTC-USDT"), eq("USDT"), eq("CROSS"), eq("NET"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getString("position_side")).thenReturn("NET");
                    when(rs.getLong("margin_units")).thenReturn(100L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.update(contains("UPDATE account_balances"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_position_margins"), any(Object[].class)))
                .thenReturn(0);
        AdlRepository repository = new AdlRepository(jdbcTemplate);

        assertThatThrownBy(() -> repository.executeAdl(
                new DeficitRow(2002L, "USDT", 500L),
                new AdlCandidate(1001L, "USDT", "BTC-USDT", AdlSide.LONG,
                        10L, 10L, 50_000L, 60_000L, 10_000L,
                        600_000L, 1_000L, 100L,
                        1_666L, 6_000_000L, 9_996L),
                500L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADL target position margin release");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), eq(50L), any(Timestamp.class),
                eq(1001L), eq("BTC-USDT"), eq("USDT"), eq("CROSS"), eq("NET"), eq(50L));
        assertThat(sql.getValue()).contains("margin_units >= ?");
        verify(jdbcTemplate, never()).update(contains("INSERT INTO account_ledger_entries"), any(Object[].class));
        verify(jdbcTemplate, never()).update(contains("INSERT INTO adl_events"), any(Object[].class));
    }

    private AdlProperties productProperties(ProductLine productLine) {
        AdlProperties properties = new AdlProperties();
        properties.getKafka().setProductLine(productLine);
        properties.getKafka().setProductTopicsEnabled(true);
        return properties;
    }

    private AdlRepository repositoryWithMarkPrice(AdlProperties properties,
                                                   String symbol,
                                                   long instrumentVersion,
                                                   long markPriceTicks) {
        LatestMarkPriceCache markPriceCache = mock(LatestMarkPriceCache.class);
        MarkPriceEvent markPrice = mock(MarkPriceEvent.class);
        when(markPrice.symbol()).thenReturn(symbol);
        when(markPrice.instrumentVersion()).thenReturn(instrumentVersion);
        when(markPrice.markPriceTicks()).thenReturn(markPriceTicks);
        when(markPriceCache.freshSnapshots(any(Duration.class))).thenReturn(List.of(markPrice));
        when(markPriceCache.fresh(eq(symbol), any(Duration.class))).thenReturn(Optional.of(markPrice));
        return new AdlRepository(jdbcTemplate, properties, markPriceCache);
    }

    @SuppressWarnings("unchecked")
    private <T> RowMapper<T> anyRowMapper() {
        return any(RowMapper.class);
    }

    private Object balanceState(org.mockito.invocation.InvocationOnMock invocation,
                                long availableUnits,
                                long lockedUnits,
                                long deficitUnits) throws Exception {
        RowMapper<?> mapper = invocation.getArgument(1);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("available_units")).thenReturn(availableUnits);
        when(rs.getLong("locked_units")).thenReturn(lockedUnits);
        when(rs.getLong("deficit_units")).thenReturn(deficitUnits);
        return mapper.mapRow(rs, 0);
    }
}
