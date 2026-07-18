package com.surprising.funding.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingRateInput;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
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
    void saveFinalRateTreatsExistingFrozenRateAsIdempotent() {
        FundingRepository repository = new FundingRepository(jdbcTemplate);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        FundingRateResponse rate = new FundingRateResponse("BTC-USDT", 11L, 110L, 100L, 10L,
                Instant.parse("2026-07-01T08:00:00Z"), 8, "PREDICTED", now);
        when(jdbcTemplate.update(contains("INSERT INTO funding_rate_ticks"), any(Object[].class)))
                .thenReturn(0);

        assertThat(repository.saveFinalRate(rate)).isFalse();
    }

    @Test
    void rateInputsDefaultToPerpetualInstruments() {
        FundingRepository repository = repositoryWithMarkPrice(new FundingProperties(), "BTC-USDT", 7L, 90L);

        repository.rateInputs(java.time.Duration.ofSeconds(10));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue())
                .contains("i.instrument_type = 'PERPETUAL'")
                .contains("i.funding_interval_hours > 0")
                .contains("JOIN mark_prices pm")
                .doesNotContain("price_mark_ticks")
                .doesNotContain("i.contract_type = ?");
        assertThat(args.getValue()).containsExactly("BTC-USDT", 7L, new BigDecimal("90"),
                new BigDecimal("89"), Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")));
    }

    @Test
    void rateInputsFilterConfiguredFundingProductLine() {
        FundingProperties properties = new FundingProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        FundingRepository repository = repositoryWithMarkPrice(properties, "BTC-USD", 8L, 90L);

        repository.rateInputs(java.time.Duration.ofSeconds(10));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue()).contains("i.contract_type = ?");
        assertThat(args.getValue()).containsExactly("BTC-USD", 8L, new BigDecimal("90"),
                new BigDecimal("89"), Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")),
                "INVERSE_PERPETUAL");
    }

    @Test
    void rateInputsSkipNonFundingProductLines() {
        FundingProperties properties = new FundingProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        FundingRepository repository = repositoryWithMarkPrice(properties, "BTC-USDT-260626", 4L, 90L);

        repository.rateInputs(java.time.Duration.ofSeconds(10));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue()).contains("1 = 0").doesNotContain("i.contract_type = ?");
        assertThat(args.getValue()).containsExactly("BTC-USDT-260626", 4L, new BigDecimal("90"),
                new BigDecimal("89"), Timestamp.from(Instant.parse("2026-07-01T00:00:00Z")));
    }

    @Test
    void dueRatesFilterConfiguredFundingProductLine() {
        FundingProperties properties = new FundingProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        FundingRepository repository = new FundingRepository(jdbcTemplate, properties);
        Instant now = Instant.parse("2026-07-01T08:00:00Z");

        repository.dueRates(now, 20);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue())
                .contains("JOIN instrument_current_versions")
                .contains("JOIN instruments i")
                .contains("i.contract_type = ?");
        assertThat(args.getValue()).containsExactly(Timestamp.from(now), "INVERSE_PERPETUAL", 20);
    }

    @Test
    void paymentCandidatesUseFreshKafkaMarkWithPositionInstrumentVersion() {
        FundingRepository repository = repositoryWithMarkPrice(new FundingProperties(), "BTC-USDT", 7L, 65_000L);
        Instant fundingTime = Instant.parse("2026-07-01T08:00:00Z");

        repository.paymentCandidates(new FundingRateResponse("BTC-USDT", 11L, 100L,
                90L, 10L, fundingTime, 8, "PREDICTED", fundingTime.minusSeconds(10)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq("BTC-USDT"),
                any(Timestamp.class), eq(65_000L), eq("LINEAR_PERPETUAL"), eq("BTC-USDT"),
                eq("LINEAR_PERPETUAL"), eq(7L));

        assertThat(sql.getValue())
                .contains("JOIN instruments i")
                .contains("i.version = p.instrument_version")
                .contains("i.contract_type = ?")
                .contains("p.product_line = ?")
                .contains("p.instrument_version = ?")
                .doesNotContain("price_mark_ticks")
                .doesNotContain("::NUMERIC")
                .doesNotContain("instrument_current_versions");
    }

    @Test
    void paymentCandidatesFilterConfiguredFundingProductLine() {
        FundingProperties properties = new FundingProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        FundingRepository repository = repositoryWithMarkPrice(properties, "BTC-USD", 9L, 65_000L);
        Instant fundingTime = Instant.parse("2026-07-01T08:00:00Z");

        repository.paymentCandidates(new FundingRateResponse("BTC-USD", 11L, 100L,
                90L, 10L, fundingTime, 8, "PREDICTED", fundingTime.minusSeconds(10)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq("BTC-USD"),
                any(Timestamp.class), eq(65_000L), eq("INVERSE_PERPETUAL"), eq("BTC-USD"),
                eq("INVERSE_PERPETUAL"), eq(9L));
        assertThat(sql.getValue())
                .contains("i.contract_type = ?")
                .contains("p.product_line = ?");
    }

    @Test
    void paymentCandidatesSkipNonFundingProductLines() {
        FundingProperties properties = new FundingProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        FundingRepository repository = new FundingRepository(jdbcTemplate, properties);
        Instant fundingTime = Instant.parse("2026-07-01T08:00:00Z");

        List<FundingPaymentCandidate> candidates = repository.paymentCandidates(new FundingRateResponse(
                "BTC-USDT", 11L, 100L, 90L, 10L, fundingTime, 8, "PREDICTED",
                fundingTime.minusSeconds(10)));

        assertThat(candidates).isEmpty();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void paymentCandidatesCalculateNotionalWithSharedLongMath() throws Exception {
        FundingRepository repository = repositoryWithMarkPrice(new FundingProperties(), "BTC-USDT", 7L, 5L);
        Instant fundingTime = Instant.parse("2026-07-01T08:00:00Z");
        when(jdbcTemplate.query(contains("WITH rate_row"), anyRowMapper(), eq("BTC-USDT"),
                any(Timestamp.class), eq(5L), eq("LINEAR_PERPETUAL"), eq("BTC-USDT"),
                eq("LINEAR_PERPETUAL"), eq(7L)))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("user_id")).thenReturn(1001L);
                    when(rs.getString("symbol")).thenReturn("BTC-USDT");
                    when(rs.getString("margin_mode")).thenReturn("ISOLATED");
                    when(rs.getString("position_side")).thenReturn("SHORT");
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
            assertThat(candidate.marginMode()).isEqualTo(MarginMode.ISOLATED);
            assertThat(candidate.positionSide()).isEqualTo(PositionSide.SHORT);
            assertThat(candidate.notionalUnits()).isEqualTo(20_000L);
            assertThat(candidate.amountUnits()).isEqualTo(2_000L);
        });
    }

    private FundingRepository repositoryWithMarkPrice(FundingProperties properties,
                                                       String symbol,
                                                       long instrumentVersion,
                                                       long markPriceTicks) {
        LatestMarkPriceCache markPriceCache = mock(LatestMarkPriceCache.class);
        MarkPriceEvent markPrice = mock(MarkPriceEvent.class);
        Instant eventTime = Instant.parse("2026-07-01T00:00:00Z");
        when(markPrice.symbol()).thenReturn(symbol);
        when(markPrice.instrumentVersion()).thenReturn(instrumentVersion);
        when(markPrice.markPriceTicks()).thenReturn(markPriceTicks);
        when(markPrice.markPrice()).thenReturn(new BigDecimal("90"));
        when(markPrice.indexPrice()).thenReturn(new BigDecimal("89"));
        when(markPrice.eventTime()).thenReturn(eventTime);
        when(markPriceCache.freshSnapshots(any(Duration.class))).thenReturn(List.of(markPrice));
        when(markPriceCache.fresh(eq(symbol), any(Duration.class))).thenReturn(java.util.Optional.of(markPrice));
        return new FundingRepository(jdbcTemplate, properties, markPriceCache);
    }

    @Test
    void fundingChargeOnlyDebitsSelectedHedgePositionMargin() throws Exception {
        FundingRepository repository = new FundingRepository(jdbcTemplate);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        FundingPaymentCandidate payment = new FundingPaymentCandidate(
                1001L, "BTC-USDT", MarginMode.CROSS, PositionSide.SHORT, "USDT",
                -10L, 1_000L, 90_000L, -90L);

        when(jdbcTemplate.queryForObject(contains("SELECT nextval"), eq(Long.class),
                eq("public.account_ledger_entry_seq"))).thenReturn(1L);
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
        when(jdbcTemplate.query(contains("FROM account_position_margins"), anyRowMapper(),
                eq(1001L), eq("LINEAR_PERPETUAL"), eq("USDT"), eq("CROSS"), eq("SHORT")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("product_line")).thenReturn("LINEAR_PERPETUAL");
                    when(rs.getString("symbol")).thenReturn("BTC-USDT");
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getString("position_side")).thenReturn("SHORT");
                    when(rs.getLong("margin_units")).thenReturn(90L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.queryForObject(contains("SELECT b.available_units"), anyRowMapper(),
                eq(1001L), eq("USDT"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("available_units")).thenReturn(0L);
                    when(rs.getLong("locked_units")).thenReturn(100L);
                    when(rs.getLong("deficit_units")).thenReturn(0L);
                    return mapper.mapRow(rs, 0);
                });

        repository.applyPaymentToAccount(3001L, payment, now);

        verify(jdbcTemplate).update(contains("UPDATE account_position_margins"),
                eq(90L), any(Timestamp.class), eq(1001L), eq("BTC-USDT"), eq("USDT"), eq("CROSS"), eq("SHORT"),
                eq("LINEAR_PERPETUAL"), eq(90L));
        verify(jdbcTemplate).update(contains("UPDATE account_balances"),
                eq(0L), eq(10L), any(Timestamp.class), eq(1001L), eq("USDT"));
        verify(jdbcTemplate).update(contains("UPDATE account_ledger_entries"),
                eq(10L), eq("3001:1001:BTC-USDT:CROSS:SHORT"), eq(1001L), eq("USDT"));
    }

    @Test
    void fundingChargeWritesConfiguredProductAccountForInversePerpetual() throws Exception {
        FundingProperties properties = new FundingProperties();
        properties.getKafka().setProductTopicsEnabled(true);
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        FundingRepository repository = new FundingRepository(jdbcTemplate, properties);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        FundingPaymentCandidate payment = new FundingPaymentCandidate(
                1001L, "BTC-USD", MarginMode.CROSS, PositionSide.NET, "BTC",
                10L, 1_000L, 90_000L, -90L);

        when(jdbcTemplate.queryForObject(contains("SELECT nextval"), eq(Long.class),
                eq("public.account_product_ledger_entry_seq"))).thenReturn(1L);
        when(jdbcTemplate.update(contains("INSERT INTO account_product_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_product_ledger_entries"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_position_margins"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_product_balances"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE account_product_deficits"), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("FROM account_position_margins"), anyRowMapper(),
                eq(1001L), eq("INVERSE_PERPETUAL"), eq("BTC"), eq("CROSS"), eq("NET")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("product_line")).thenReturn("INVERSE_PERPETUAL");
                    when(rs.getString("symbol")).thenReturn("BTC-USD");
                    when(rs.getString("asset")).thenReturn("BTC");
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getString("position_side")).thenReturn("NET");
                    when(rs.getLong("margin_units")).thenReturn(90L);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.queryForObject(contains("FROM account_product_balances b"), anyRowMapper(),
                eq("COIN_PERPETUAL"), eq(1001L), eq("BTC"))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("available_units")).thenReturn(0L);
                    when(rs.getLong("locked_units")).thenReturn(90L);
                    when(rs.getLong("deficit_units")).thenReturn(0L);
                    return mapper.mapRow(rs, 0);
                });

        repository.applyPaymentToAccount(3001L, payment, now);

        ArgumentCaptor<String> selectSql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(selectSql.capture(), anyRowMapper(),
                eq(1001L), eq("INVERSE_PERPETUAL"), eq("BTC"), eq("CROSS"), eq("NET"));
        assertThat(selectSql.getValue()).contains("product_line = ?");
        verify(jdbcTemplate).update(contains("INSERT INTO account_product_ledger_entries"),
                eq(1L), eq(1001L), eq("COIN_PERPETUAL"), eq("BTC"), eq(-90L),
                eq("3001:1001:BTC-USD:CROSS:NET"), eq("FUNDING_PAID"), any(Timestamp.class));
        verify(jdbcTemplate).update(contains("UPDATE account_position_margins"),
                eq(90L), any(Timestamp.class), eq(1001L), eq("BTC-USD"), eq("BTC"), eq("CROSS"), eq("NET"),
                eq("INVERSE_PERPETUAL"), eq(90L));
        verify(jdbcTemplate).update(contains("UPDATE account_product_balances"),
                eq(0L), eq(0L), any(Timestamp.class), eq("COIN_PERPETUAL"), eq(1001L), eq("BTC"));
        verify(jdbcTemplate).update(contains("UPDATE account_product_deficits"),
                eq(0L), any(Timestamp.class), eq("COIN_PERPETUAL"), eq(1001L), eq("BTC"));
        verify(jdbcTemplate).update(contains("UPDATE account_product_ledger_entries"),
                eq(0L), eq("3001:1001:BTC-USD:CROSS:NET"), eq(1001L), eq("COIN_PERPETUAL"), eq("BTC"));
    }

    @Test
    void fundingChargeOnlyDebitsPositionMarginLockedCollateral() throws Exception {
        FundingRepository repository = new FundingRepository(jdbcTemplate);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        FundingPaymentCandidate payment = new FundingPaymentCandidate(
                1001L, "BTC-USDT", "USDT", 10L, 1_000L, 90_000L, -90L);

        when(jdbcTemplate.queryForObject(contains("SELECT nextval"), eq(Long.class),
                eq("public.account_ledger_entry_seq"))).thenReturn(1L);
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
        when(jdbcTemplate.query(contains("FROM account_position_margins"), anyRowMapper(),
                eq(1001L), eq("LINEAR_PERPETUAL"), eq("USDT"), eq("CROSS"), eq("NET")))
                .thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("product_line")).thenReturn("LINEAR_PERPETUAL");
                    when(rs.getString("symbol")).thenReturn("ETH-USDT");
                    when(rs.getString("asset")).thenReturn("USDT");
                    when(rs.getString("margin_mode")).thenReturn("CROSS");
                    when(rs.getString("position_side")).thenReturn("NET");
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
                eq(50L), any(Timestamp.class), eq(1001L), eq("ETH-USDT"), eq("USDT"), eq("CROSS"), eq("NET"),
                eq("LINEAR_PERPETUAL"), eq(50L));
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
        when(jdbcTemplate.queryForObject(contains("SELECT nextval"), eq(Long.class),
                eq("public.account_ledger_entry_seq"))).thenReturn(1L);
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
        when(jdbcTemplate.queryForObject(contains("SELECT nextval"), eq(Long.class),
                eq("public.account_ledger_entry_seq"))).thenReturn(1L);
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
