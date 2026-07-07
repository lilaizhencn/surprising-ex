package com.surprising.risk.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.risk.provider.model.PositionRiskTarget;
import com.surprising.risk.provider.model.RiskGroupKey;
import java.sql.Timestamp;
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
class RiskRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void saveAccountSnapshotFailsWhenInsertIsSkipped() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.update(contains("INSERT INTO risk_account_snapshots"), any(Object[].class)))
                .thenReturn(0);

        assertThatThrownBy(() -> repository.saveAccountSnapshot(new RiskAccountSnapshotResponse(
                101L, 1001L, "USDT", 10_000L, -9_000L, 1_000L,
                1_100L, 1_100_000L, RiskStatus.LIQUIDATION,
                Instant.parse("2026-07-01T00:00:00Z"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("risk account snapshot insert");
    }

    @Test
    void savePositionSnapshotFailsWhenInsertIsSkipped() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.update(contains("INSERT INTO risk_position_snapshots"), any(Object[].class)))
                .thenReturn(0);

        CalculatedPositionRisk position = new CalculatedPositionRisk(1001L, "BTC-USDT", 7L, "USDT",
                10L, 65_000L, 60_000L, 600_000L, -50_000L, 100_000L);

        assertThatThrownBy(() -> repository.savePositionSnapshot(101L, position, 1_100_000L,
                RiskStatus.LIQUIDATION, Instant.parse("2026-07-01T00:00:00Z")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("risk position snapshot insert");
    }

    @Test
    void liquidationCandidateConflictOnlyIgnoresActiveCandidateConflict() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.update(contains("INSERT INTO risk_liquidation_candidates"), any(Object[].class)))
                .thenReturn(0);
        RiskAccountSnapshotResponse account = new RiskAccountSnapshotResponse(
                101L, 1001L, "USDT", 10_000L, -9_000L, 1_000L,
                1_100L, 1_100_000L, RiskStatus.LIQUIDATION,
                Instant.parse("2026-07-01T00:00:00Z"));
        CalculatedPositionRisk position = new CalculatedPositionRisk(1001L, "BTC-USDT", 7L, "USDT",
                10L, 65_000L, 60_000L, 600_000L, -50_000L, 100_000L);

        long insertedId = repository.createLiquidationCandidate(account, position, RiskStatus.LIQUIDATION,
                1_100_000L, 901L, Instant.parse("2026-07-01T00:00:00Z"));

        assertThat(insertedId).isZero();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("ON CONFLICT (user_id, symbol, margin_mode, position_side) WHERE status IN ('NEW', 'PROCESSING') DO NOTHING")
                .doesNotContain("ON CONFLICT DO NOTHING");
    }

    @Test
    void acquireScanLeaseUsesRiskGroupKeyAndLeaseExpiryGuard() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("INSERT INTO risk_scan_leases"), anyRowMapper(),
                eq(1001L), eq("USDT_PERPETUAL"), eq("USDT"), eq("risk-node-a"), any(Timestamp.class),
                any(Timestamp.class)))
                .thenReturn(List.of("risk-node-a"));

        boolean acquired = repository.acquireScanLease(new RiskGroupKey(1001L, "USDT"),
                "risk-node-a", Duration.ofSeconds(15));

        assertThat(acquired).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(1001L), eq("USDT_PERPETUAL"), eq("USDT"),
                eq("risk-node-a"), any(Timestamp.class), any(Timestamp.class));
        assertThat(sql.getValue())
                .contains("ON CONFLICT (user_id, account_type, settle_asset) DO UPDATE")
                .contains("risk_scan_leases.owner_id = EXCLUDED.owner_id")
                .contains("risk_scan_leases.lease_until <= EXCLUDED.updated_at")
                .contains("RETURNING owner_id");
    }

    @Test
    void acquireScanLeaseReturnsFalseWhenAnotherLiveOwnerHoldsLease() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("INSERT INTO risk_scan_leases"), anyRowMapper(),
                eq(1001L), eq("USDT_PERPETUAL"), eq("USDT"), eq("risk-node-b"), any(Timestamp.class),
                any(Timestamp.class)))
                .thenReturn(List.of());

        boolean acquired = repository.acquireScanLease(new RiskGroupKey(1001L, "USDT"),
                "risk-node-b", Duration.ofSeconds(15));

        assertThat(acquired).isFalse();
    }

    @Test
    void riskGroupsUsesFreshMarkKeysetPagination() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(10_000L),
                eq(1001L), eq(1001L), eq(1001L), eq("USDT_PERPETUAL"), eq(1001L),
                eq("USDT_PERPETUAL"), eq("USDT"), eq(200))).thenReturn(List.of());

        repository.riskGroups(Duration.ofSeconds(10), new RiskGroupKey(1001L, "USDT"), 200);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(10_000L),
                eq(1001L), eq(1001L), eq(1001L), eq("USDT_PERPETUAL"), eq(1001L),
                eq("USDT_PERPETUAL"), eq("USDT"), eq(200));
        assertThat(sql.getValue())
                .contains("END AS account_type")
                .contains("bool_and(event_time IS NOT NULL")
                .contains("GROUP BY user_id, account_type, settle_asset")
                .contains("WHERE all_marks_fresh")
                .contains("user_id > ?")
                .contains("account_type > ?")
                .contains("ORDER BY user_id ASC, account_type ASC, settle_asset ASC")
                .contains("LIMIT ?");
    }

    @Test
    void riskGroupsFilterConfiguredProductLineWhenProductTopicsAreEnabled() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        RiskRepository repository = new RiskRepository(jdbcTemplate, properties);

        repository.riskGroups(Duration.ofSeconds(10), null, 50);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue()).contains("i.contract_type = ?");
        assertThat(args.getValue()).containsExactly(
                "VANILLA_OPTION", 10_000L, 0L, 0L, 0L, "", 0L, "", "", 50);
    }

    @Test
    void latestPositionsFilterConfiguredProductLineWhenProductTopicsAreEnabled() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        RiskRepository repository = new RiskRepository(jdbcTemplate, properties);

        repository.latestPositions(1001L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue())
                .contains("JOIN instruments i ON i.symbol = s.symbol AND i.version = s.instrument_version")
                .contains("WHERE s.user_id = ?")
                .contains("i.contract_type = ?")
                .contains("ORDER BY s.symbol ASC, s.margin_mode ASC, s.position_side ASC, s.event_time DESC");
        assertThat(args.getValue()).containsExactly(1001L, "LINEAR_DELIVERY");
    }

    @Test
    void riskTargetForPositionEventUsesEventVersionOrCurrentVersionFallback() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(1001L), eq("BTC-USDT"),
                eq(7L), eq(7L), eq("BTC-USDT")))
                .thenReturn(List.of(new PositionRiskTarget(1001L, "BTC-USDT", 7L, "USDT")));

        Optional<PositionRiskTarget> target = repository.riskTargetForPositionEvent(1001L, "BTC-USDT", 7L);

        assertThat(target).contains(new PositionRiskTarget(1001L, "BTC-USDT", 7L, "USDT"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(1001L), eq("BTC-USDT"),
                eq(7L), eq(7L), eq("BTC-USDT"));
        assertThat(sql.getValue())
                .contains("i.version AS instrument_version")
                .contains("WHEN ? > 0 THEN ?")
                .contains("instrument_current_versions")
                .contains("WHERE cv.symbol = ?");
    }

    @Test
    void hasOpenPositionsChecksRiskGroupWithoutMarkFreshnessFilter() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(1001L), eq("USDT_PERPETUAL"), eq("USDT")))
                .thenReturn(List.of(1));

        boolean exists = repository.hasOpenPositions(new RiskGroupKey(1001L, "USDT"));

        assertThat(exists).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(1001L), eq("USDT_PERPETUAL"), eq("USDT"));
        assertThat(sql.getValue())
                .contains("FROM account_positions p")
                .contains("JOIN instruments i ON i.symbol = p.symbol AND i.version = p.instrument_version")
                .contains("END = ?")
                .contains("p.signed_quantity_steps <> 0")
                .doesNotContain("price_mark_ticks");
    }

    @Test
    void walletBalanceUsesCoinProductAccountForInversePerpetualRiskGroup() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(),
                eq("COIN_PERPETUAL"), eq(1001L), eq("BTC"), eq(1001L), eq("BTC"),
                eq(1001L), eq("BTC"), eq(1001L), eq("BTC"))).thenReturn(List.of(123L));

        long walletBalanceUnits = repository.walletBalanceUnits(1001L, "COIN_PERPETUAL", "BTC");

        assertThat(walletBalanceUnits).isEqualTo(123L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq("COIN_PERPETUAL"), eq(1001L), eq("BTC"), eq(1001L), eq("BTC"),
                eq(1001L), eq("BTC"), eq(1001L), eq("BTC"));
        assertThat(sql.getValue())
                .contains("SELECT ? AS account_type")
                .contains("WHEN 'LINEAR_DELIVERY' THEN 'USDT_DELIVERY'")
                .contains("WHEN 'INVERSE_DELIVERY' THEN 'COIN_DELIVERY'")
                .contains("r.account_type = ctx.account_type")
                .contains("WHEN ctx.account_type = 'USDT_PERPETUAL'")
                .contains("LEFT JOIN account_product_balances pb")
                .contains("LEFT JOIN account_product_deficits pd");
    }

    @Test
    void calculatePositionsUsesRiskBracketMaintenanceMarginRate() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(10_000L))).thenReturn(List.of());

        repository.calculatePositions(Duration.ofSeconds(10));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(10_000L));
        assertThat(sql.getValue())
                .contains("instrument_risk_brackets")
                .contains("b.notional_floor_units <= pi.bracket_notional_units")
                .contains("ORDER BY b.notional_floor_units DESC")
                .contains("COALESCE(br.maintenance_margin_rate_ppm")
                .contains("pi.base_maintenance_margin_rate_ppm")
                .contains("i.contract_type IN ('INVERSE_PERPETUAL', 'INVERSE_DELIVERY')");
    }

    @Test
    void calculatePositionsForGroupFiltersConfiguredProductLine() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        RiskRepository repository = new RiskRepository(jdbcTemplate, properties);

        repository.calculatePositions(new RiskGroupKey(1001L, "OPTION", "USDT"), Duration.ofSeconds(10));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue()).contains("i.contract_type = ?");
        assertThat(args.getValue()).containsExactly(
                10_000L, 1001L, "OPTION", "USDT", "VANILLA_OPTION",
                1001L, "OPTION", "USDT", 10_000L, "VANILLA_OPTION");
    }

    @Test
    void calculatePositionsForGroupRequiresWholeGroupFreshness() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(10_000L),
                eq(1001L), eq("USDT_PERPETUAL"), eq("USDT"), eq(1001L), eq("USDT_PERPETUAL"), eq("USDT"),
                eq(10_000L))).thenReturn(List.of());

        repository.calculatePositions(new RiskGroupKey(1001L, "USDT"), Duration.ofSeconds(10));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(10_000L),
                eq(1001L), eq("USDT_PERPETUAL"), eq("USDT"), eq(1001L), eq("USDT_PERPETUAL"), eq("USDT"),
                eq(10_000L));
        assertThat(sql.getValue())
                .contains("WITH group_freshness AS")
                .contains("COALESCE(bool_and(pm.event_time IS NOT NULL")
                .contains("AND p.user_id = ?")
                .contains("END = ?")
                .contains("AND i.settle_asset = ?")
                .contains("CROSS JOIN group_freshness gf")
                .contains("AND gf.all_marks_fresh")
                .contains("instrument_risk_brackets")
                .contains("i.contract_type IN ('INVERSE_PERPETUAL', 'INVERSE_DELIVERY')");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
