package com.surprising.liquidation.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.instrument.api.model.ContractType;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.LiquidationSizingInput;
import com.surprising.liquidation.api.model.LiquidationOrderStatus;
import com.surprising.liquidation.provider.model.LiquidationPricingDecision;
import com.surprising.liquidation.provider.model.LiquidationPricingInput;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import java.sql.Timestamp;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(1001L), eq("LINEAR_PERPETUAL"),
                eq("USDT_PERPETUAL"),
                eq("USDT"), eq(7000L)))
                .thenReturn(List.of(RiskStatus.LIQUIDATION));

        RiskStatus status = repository.latestRiskStatus(1001L, "USDT", Duration.ofSeconds(7));

        assertThat(status).isEqualTo(RiskStatus.LIQUIDATION);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(1001L), eq("LINEAR_PERPETUAL"), eq("USDT_PERPETUAL"),
                eq("USDT"), eq(7000L));
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .contains("event_time >= now() - (? * INTERVAL '1 millisecond')");
    }

    @Test
    void staleOrMissingRiskSnapshotIsTreatedAsNormal() {
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(1001L), eq("LINEAR_PERPETUAL"), eq("USDT_PERPETUAL"),
                eq("USDT"), eq(5000L)))
                .thenReturn(List.of());

        assertThat(repository.latestRiskStatus(1001L, "USDT", Duration.ofSeconds(5)))
                .isEqualTo(RiskStatus.NORMAL);
    }

    @Test
    void latestRiskStatusFiltersByAccountType() {
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(1001L), eq("LINEAR_DELIVERY"), eq("USDT_DELIVERY"),
                eq("USDT"), eq(5000L)))
                .thenReturn(List.of(RiskStatus.WARNING));

        RiskStatus status = repository.latestRiskStatus(1001L, "USDT_DELIVERY", "USDT", Duration.ofSeconds(5));

        assertThat(status).isEqualTo(RiskStatus.WARNING);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(1001L), eq("LINEAR_DELIVERY"), eq("USDT_DELIVERY"),
                eq("USDT"), eq(5000L));
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .contains("account_type = ?");
    }

    @Test
    void claimCandidateLeavesLegacyTopicsUnfiltered() {
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(9401L))).thenReturn(List.of());

        repository.claimCandidate(9401L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(9401L));
        assertThat(sql.getValue())
                .doesNotContain("FROM instruments i")
                .doesNotContain("c.product_line = ?");
    }

    @Test
    void claimCandidateFiltersByProductLineWhenProductTopicsAreEnabled() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(9401L), eq("OPTION")))
                .thenReturn(List.of());

        repository.claimCandidate(9401L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(9401L), eq("OPTION"));
        assertThat(sql.getValue())
                .doesNotContain("FROM instruments i")
                .contains("c.product_line = ?");
    }

    @Test
    void ordersFiltersByProductLineWhenProductTopicsAreEnabled() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(2002L), eq(2002L),
                eq("LINEAR_DELIVERY"), eq(25))).thenReturn(List.of());

        repository.orders(2002L, 25);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(2002L), eq(2002L),
                eq("LINEAR_DELIVERY"), eq(25));
        assertThat(sql.getValue())
                .contains("SELECT lo.*")
                .contains("JOIN risk_liquidation_candidates c")
                .doesNotContain("JOIN instruments i")
                .contains("c.product_line = ?")
                .contains("ORDER BY lo.created_at DESC");
    }

    @Test
    void ordersPageFiltersByProductLineAndUsesAliasedCursorColumns() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(2002L), eq(2002L),
                eq("INVERSE_DELIVERY"), eq(26))).thenReturn(List.of());

        repository.ordersPage(2002L, 25, null, "createdAt.asc");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(2002L), eq(2002L),
                eq("INVERSE_DELIVERY"), eq(26));
        assertThat(sql.getValue())
                .contains("JOIN risk_liquidation_candidates c")
                .doesNotContain("JOIN instruments i")
                .contains("c.product_line = ?")
                .contains("ORDER BY lo.created_at ASC, lo.liquidation_order_id ASC");
    }

    @Test
    void ordersByCandidateFiltersByProductLineWhenProductTopicsAreEnabled() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(9401L), eq("OPTION")))
                .thenReturn(List.of());

        repository.ordersByCandidate(9401L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(9401L), eq("OPTION"));
        assertThat(sql.getValue())
                .contains("WHERE lo.candidate_id = ?")
                .contains("JOIN risk_liquidation_candidates c")
                .doesNotContain("JOIN instruments i")
                .contains("c.product_line = ?");
    }

    @Test
    void candidateDetailsFilterByProductLineWhenProductTopicsAreEnabled() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate, properties);
        when(jdbcTemplate.queryForList(any(String.class), eq(9401L), eq("OPTION")))
                .thenReturn(List.of());

        repository.candidate(9401L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), eq(9401L), eq("OPTION"));
        assertThat(sql.getValue())
                .doesNotContain("JOIN instruments i")
                .contains("c.product_line = ?");
    }

    @Test
    void lockOpenReduceOnlyStepsFailsOnOverflow() {
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("FROM trading_orders"), anyRowMapper(),
                eq(1001L), eq("LINEAR_PERPETUAL"), eq("BTC-USDT"), eq("CROSS"), eq("NET"), eq(7L), eq("SELL")))
                .thenReturn(List.of(Long.MAX_VALUE, 1L));

        assertThatThrownBy(() -> repository.lockOpenReduceOnlySteps(1001L, "BTC-USDT", 7L,
                com.surprising.trading.api.model.OrderSide.SELL))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void lockOpenReduceOnlyStepsFiltersByCurrentProductLine() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(),
                eq(1001L), eq("LINEAR_DELIVERY"), eq("BTC-USDT"), eq("CROSS"), eq("NET"), eq(7L), eq("SELL")))
                .thenReturn(List.of(2L, 3L));

        long steps = repository.lockOpenReduceOnlySteps(1001L, "BTC-USDT", 7L, OrderSide.SELL);

        assertThat(steps).isEqualTo(5L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq(1001L), eq("LINEAR_DELIVERY"), eq("BTC-USDT"), eq("CROSS"), eq("NET"), eq(7L), eq("SELL"));
        assertThat(sql.getValue())
                .contains("product_line = ?")
                .contains("FOR UPDATE");
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
        LatestMarkPriceCache markPriceCache = mock(LatestMarkPriceCache.class);
        MarkPriceEvent markPrice = mock(MarkPriceEvent.class);
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate,
                new LiquidationProperties(), markPriceCache);
        when(markPriceCache.fresh("BTC-USDT", Duration.ofSeconds(5))).thenReturn(Optional.of(markPrice));
        when(markPrice.instrumentVersion()).thenReturn(7L);
        when(markPrice.markPriceTicks()).thenReturn(5L);
        when(jdbcTemplate.query(contains("FROM account_positions p"), anyRowMapper(),
                eq(5L), eq(2002L), eq("BTC-USDT"), eq("CROSS"), eq("NET"), eq(7L),
                eq("LINEAR_PERPETUAL"))).thenAnswer(invocation -> {
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
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(5L), eq(2002L), eq("BTC-USDT"), eq("CROSS"),
                eq("NET"), eq(7L), eq("LINEAR_PERPETUAL"));
        assertThat(sql.getValue())
                .contains("p.product_line = ?")
                .doesNotContain("price_mark_ticks")
                .doesNotContain("::NUMERIC")
                .doesNotContain("abs(");
    }

    @Test
    void latestPricingInputUsesFreshRiskPositionAndAccountSnapshot() throws Exception {
        LatestMarkPriceCache markPriceCache = mock(LatestMarkPriceCache.class);
        MarkPriceEvent markPrice = mock(MarkPriceEvent.class);
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate,
                new LiquidationProperties(), markPriceCache);
        when(markPriceCache.fresh("BTC-USDT", Duration.ofSeconds(5))).thenReturn(Optional.of(markPrice));
        when(markPrice.instrumentVersion()).thenReturn(8L);
        when(markPrice.markPriceTicks()).thenReturn(100L);
        when(jdbcTemplate.query(contains("FROM risk_position_snapshots ps"), anyRowMapper(),
                eq(100L), eq(2002L), eq("LINEAR_PERPETUAL"), eq("BTC-USDT"), eq("ISOLATED"), eq("NET"), eq(8L),
                eq(5000L))).thenAnswer(invocation -> {
                    RowMapper<?> mapper = invocation.getArgument(1);
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getString("contract_type")).thenReturn("LINEAR_PERPETUAL");
                    when(rs.getLong("signed_quantity_steps")).thenReturn(10L);
                    when(rs.getLong("mark_price_ticks")).thenReturn(100L);
                    when(rs.getLong("equity_units")).thenReturn(200L);
                    when(rs.getLong("maintenance_margin_units")).thenReturn(50L);
                    when(rs.getLong("notional_multiplier_units")).thenReturn(1L);
                    when(rs.getLong("price_tick_units")).thenReturn(1L);
                    when(rs.getLong("settle_scale_units")).thenReturn(100_000_000L);
                    return List.of(mapper.mapRow(rs, 0));
                });

        LiquidationPricingInput input = repository.latestPricingInput(2002L, "BTC-USDT", MarginMode.ISOLATED,
                8L, Duration.ofSeconds(5)).orElseThrow();

        assertThat(input).isEqualTo(new LiquidationPricingInput(ContractType.LINEAR_PERPETUAL,
                10L, 100L, 200L, 50L, 1L, 1L, 100_000_000L));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(100L), eq(2002L), eq("LINEAR_PERPETUAL"), eq("BTC-USDT"),
                eq("ISOLATED"), eq("NET"), eq(8L), eq(5000L));
        assertThat(sql.getValue())
                .contains("ps.product_line = ?")
                .contains("acc.product_line = ps.product_line")
                .contains("ps.event_time >= now() - (? * INTERVAL '1 millisecond')")
                .contains("ps.position_margin_units + ps.unrealized_pnl_units")
                .contains("acc.equity_units");
    }

    @Test
    void insertLiquidationOrderPersistsPricingAuditFields() {
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate);
        when(jdbcTemplate.update(contains("INSERT INTO liquidation_orders"), any(Object[].class)))
                .thenReturn(1);

        boolean inserted = repository.insertLiquidationOrder(6001L, 9401L, 7001L, 2002L, "BTC-USDT",
                MarginMode.CROSS, OrderSide.SELL, 5L, LiquidationOrderStatus.SUBMITTED, "PARTIAL_LIQUIDATION",
                new LiquidationPricingDecision(80L, 81L, 3_000L, 3L),
                Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(inserted).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("bankruptcy_price_ticks")
                .contains("takeover_price_ticks")
                .contains("liquidation_fee_rate_ppm")
                .contains("liquidation_fee_units");
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

    @Test
    void updateOrderLifecycleFiltersByProductLineWhenProductTopicsAreEnabled() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq("FILLED"), eq(7001L),
                eq("INVERSE_DELIVERY"))).thenReturn(List.of());

        assertThat(repository.updateOrderLifecycle(7001L, LiquidationOrderStatus.FILLED, "COMPLETED"))
                .isEmpty();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq("FILLED"), eq(7001L),
                eq("INVERSE_DELIVERY"));
        assertThat(sql.getValue())
                .contains("FROM risk_liquidation_candidates c")
                .contains("c.candidate_id = lo.candidate_id")
                .contains("c.product_line = ?")
                .doesNotContain("JOIN instruments i");
    }

    @Test
    void cancelCandidateFiltersByProductLineWhenProductTopicsAreEnabled() {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate, properties);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(Timestamp.from(now)), eq(9401L),
                eq("LINEAR_PERPETUAL"))).thenReturn(List.of());

        repository.cancelCandidateIfSafe(9401L, now);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(Timestamp.from(now)), eq(9401L),
                eq("LINEAR_PERPETUAL"));
        assertThat(sql.getValue())
                .doesNotContain("FROM instruments i")
                .contains("c.product_line = ?");
    }

    @SuppressWarnings("unchecked")
    private <T> RowMapper<T> anyRowMapper() {
        return any(RowMapper.class);
    }
}
