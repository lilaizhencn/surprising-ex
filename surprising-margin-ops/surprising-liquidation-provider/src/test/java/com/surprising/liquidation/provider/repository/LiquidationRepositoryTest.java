package com.surprising.liquidation.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.liquidation.api.model.LiquidationOrderStatus;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.ClaimedCandidate;
import com.surprising.liquidation.provider.model.LiquidationPricingDecision;
import com.surprising.liquidation.provider.repository.LiquidationAuditRepository.LiquidationOrderInsert;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.PositionSide;
import java.sql.Timestamp;
import java.time.Instant;
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
    void candidateRepositoryOnlyUpdatesCandidateTable() {
        LiquidationCandidateRepository repository = new LiquidationCandidateRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(9401L))).thenReturn(List.of());

        repository.claimAll(List.of(9401L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(9401L));
        assertThat(sql.getValue())
                .contains("UPDATE risk_liquidation_candidates")
                .doesNotContain(" JOIN ");
    }

    @Test
    void candidateRepositoryFiltersProductLineWhenProductTopicsAreEnabled() {
        LiquidationProperties properties = properties(ProductLine.OPTION);
        LiquidationCandidateRepository repository = new LiquidationCandidateRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(9401L), eq("OPTION")))
                .thenReturn(List.of());

        repository.claimAll(List.of(9401L));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(9401L), eq("OPTION"));
        assertThat(sql.getValue()).contains("c.product_line = ?");
    }

    @Test
    void candidateStatusUpdateFailsClosedWhenTargetDoesNotExist() {
        LiquidationCandidateRepository repository = new LiquidationCandidateRepository(jdbcTemplate);
        when(jdbcTemplate.update(any(String.class), eq("COMPLETED"), eq(9401L))).thenReturn(0);

        assertThatThrownBy(() -> repository.updateStatus(9401L, "COMPLETED"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("更新强平候选状态失败");
    }

    @Test
    void positionRepositoryOnlyLocksPositionTable() {
        LiquidationPositionRepository repository =
                new LiquidationPositionRepository(jdbcTemplate, new LiquidationProperties());
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class))).thenReturn(List.of());

        repository.lockAll(List.of(new ClaimedCandidate(
                9401L, 9301L, 2002L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET, 8L,
                "USDT_PERPETUAL", "USDT", 10L, 590_000L, 1_000L, 500L, 1_100_000L)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("JOIN account_positions p")
                .contains("FOR UPDATE OF p")
                .doesNotContain("risk_position_snapshots")
                .doesNotContain("trading_orders");
    }

    @Test
    void auditRepositoryQueriesItsOwnProductLineWithoutJoiningCandidateTable() {
        LiquidationProperties properties = properties(ProductLine.LINEAR_DELIVERY);
        LiquidationAuditRepository repository = new LiquidationAuditRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(2002L), eq(2002L),
                eq("LINEAR_DELIVERY"), eq(25))).thenReturn(List.of());

        repository.find(2002L, 25);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(2002L), eq(2002L),
                eq("LINEAR_DELIVERY"), eq(25));
        assertThat(sql.getValue())
                .contains("FROM liquidation_orders lo")
                .contains("lo.product_line = ?")
                .doesNotContain("JOIN risk_liquidation_candidates");
    }

    @Test
    void auditRepositoryUsesAliasedCursorColumns() {
        LiquidationProperties properties = properties(ProductLine.INVERSE_DELIVERY);
        LiquidationAuditRepository repository = new LiquidationAuditRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(2002L), eq(2002L),
                eq("INVERSE_DELIVERY"), eq(26))).thenReturn(List.of());

        repository.page(2002L, 25, null, "createdAt.asc");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(2002L), eq(2002L),
                eq("INVERSE_DELIVERY"), eq(26));
        assertThat(sql.getValue())
                .contains("lo.product_line = ?")
                .contains("ORDER BY lo.created_at ASC, lo.liquidation_order_id ASC");
    }

    @Test
    void auditInsertPersistsProductLineAndPricingFields() {
        LiquidationProperties properties = properties(ProductLine.LINEAR_DELIVERY);
        LiquidationAuditRepository repository = new LiquidationAuditRepository(jdbcTemplate, properties);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");

        boolean inserted = repository.insert(new LiquidationOrderInsert(
                6001L, 9401L, 7001L, 2002L, "BTC-USDT", MarginMode.CROSS, PositionSide.NET,
                OrderSide.SELL, 5L, LiquidationOrderStatus.SUBMITTED, "PARTIAL_LIQUIDATION",
                new LiquidationPricingDecision(80L, 81L, 3_000L, 3L), now));

        assertThat(inserted).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("product_line")
                .contains("bankruptcy_price_ticks")
                .contains("liquidation_fee_units");
        assertThat(args.getValue())
                .contains("LINEAR_DELIVERY", 80L, 81L, 3_000L, 3L, Timestamp.from(now));
    }

    @Test
    void auditLifecycleUpdateOnlyTouchesAuditTable() {
        LiquidationProperties properties = properties(ProductLine.INVERSE_DELIVERY);
        LiquidationAuditRepository repository = new LiquidationAuditRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq("FILLED"), eq(7001L),
                eq("INVERSE_DELIVERY"))).thenReturn(List.of(9401L));

        assertThat(repository.updateStatusByOrderId(7001L, LiquidationOrderStatus.FILLED))
                .contains(9401L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq("FILLED"), eq(7001L),
                eq("INVERSE_DELIVERY"));
        assertThat(sql.getValue())
                .contains("UPDATE liquidation_orders")
                .contains("lo.product_line = ?")
                .doesNotContain("risk_liquidation_candidates");
    }

    @Test
    void consistencyRepositoryKeepsAtomicCandidateCancellationCheck() {
        LiquidationProperties properties = properties(ProductLine.LINEAR_PERPETUAL);
        LiquidationRepository repository = new LiquidationRepository(jdbcTemplate, properties);
        Instant now = Instant.parse("2026-07-01T00:00:00Z");
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(Timestamp.from(now)), eq(9401L),
                eq("LINEAR_PERPETUAL"))).thenReturn(List.of());

        repository.cancelCandidateIfSafe(9401L, now);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(Timestamp.from(now)), eq(9401L),
                eq("LINEAR_PERPETUAL"));
        assertThat(sql.getValue())
                .contains("UPDATE risk_liquidation_candidates")
                .contains("NOT EXISTS")
                .contains("FROM liquidation_orders")
                .contains("c.product_line = ?");
    }

    private LiquidationProperties properties(ProductLine productLine) {
        LiquidationProperties properties = new LiquidationProperties();
        properties.getKafka().setProductLine(productLine);
        properties.getKafka().setProductTopicsEnabled(true);
        return properties;
    }

    @SuppressWarnings("unchecked")
    private <T> RowMapper<T> anyRowMapper() {
        return any(RowMapper.class);
    }
}
