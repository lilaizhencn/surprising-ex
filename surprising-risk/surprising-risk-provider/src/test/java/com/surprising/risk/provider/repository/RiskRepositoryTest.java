package com.surprising.risk.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
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
                .contains("ON CONFLICT (user_id, symbol) WHERE status IN ('NEW', 'PROCESSING') DO NOTHING")
                .doesNotContain("ON CONFLICT DO NOTHING");
    }

    @Test
    void acquireScanLeaseUsesRiskGroupKeyAndLeaseExpiryGuard() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("INSERT INTO risk_scan_leases"), anyRowMapper(),
                eq(1001L), eq("USDT"), eq("risk-node-a"), any(Timestamp.class), any(Timestamp.class)))
                .thenReturn(List.of("risk-node-a"));

        boolean acquired = repository.acquireScanLease(new RiskGroupKey(1001L, "USDT"),
                "risk-node-a", Duration.ofSeconds(15));

        assertThat(acquired).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(1001L), eq("USDT"),
                eq("risk-node-a"), any(Timestamp.class), any(Timestamp.class));
        assertThat(sql.getValue())
                .contains("ON CONFLICT (user_id, settle_asset) DO UPDATE")
                .contains("risk_scan_leases.owner_id = EXCLUDED.owner_id")
                .contains("risk_scan_leases.lease_until <= EXCLUDED.updated_at")
                .contains("RETURNING owner_id");
    }

    @Test
    void acquireScanLeaseReturnsFalseWhenAnotherLiveOwnerHoldsLease() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.query(contains("INSERT INTO risk_scan_leases"), anyRowMapper(),
                eq(1001L), eq("USDT"), eq("risk-node-b"), any(Timestamp.class), any(Timestamp.class)))
                .thenReturn(List.of());

        boolean acquired = repository.acquireScanLease(new RiskGroupKey(1001L, "USDT"),
                "risk-node-b", Duration.ofSeconds(15));

        assertThat(acquired).isFalse();
    }

    @Test
    void riskGroupsUsesFreshMarkKeysetPagination() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(10_000L),
                eq(1001L), eq(1001L), eq(1001L), eq("USDT"), eq(200))).thenReturn(List.of());

        repository.riskGroups(Duration.ofSeconds(10), new RiskGroupKey(1001L, "USDT"), 200);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(10_000L),
                eq(1001L), eq(1001L), eq(1001L), eq("USDT"), eq(200));
        assertThat(sql.getValue())
                .contains("bool_and(pm.event_time IS NOT NULL")
                .contains("GROUP BY p.user_id, i.settle_asset")
                .contains("WHERE all_marks_fresh")
                .contains("p.user_id > ? OR (p.user_id = ? AND i.settle_asset > ?)")
                .contains("ORDER BY user_id ASC, settle_asset ASC")
                .contains("LIMIT ?");
    }

    @Test
    void riskGroupForPositionEventUsesEventVersionOrCurrentVersionFallback() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(1001L), eq("BTC-USDT"),
                eq(7L), eq(7L), eq("BTC-USDT"))).thenReturn(List.of(new RiskGroupKey(1001L, "USDT")));

        Optional<RiskGroupKey> key = repository.riskGroupForPositionEvent(1001L, "BTC-USDT", 7L);

        assertThat(key).contains(new RiskGroupKey(1001L, "USDT"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(1001L), eq("BTC-USDT"),
                eq(7L), eq(7L), eq("BTC-USDT"));
        assertThat(sql.getValue())
                .contains("WHEN ? > 0 THEN ?")
                .contains("instrument_current_versions")
                .contains("WHERE cv.symbol = ?");
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
                .contains("pi.base_maintenance_margin_rate_ppm");
    }

    @Test
    void calculatePositionsForGroupRequiresWholeGroupFreshness() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), eq(10_000L),
                eq(1001L), eq("USDT"), eq(1001L), eq("USDT"), eq(10_000L))).thenReturn(List.of());

        repository.calculatePositions(new RiskGroupKey(1001L, "USDT"), Duration.ofSeconds(10));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), eq(10_000L),
                eq(1001L), eq("USDT"), eq(1001L), eq("USDT"), eq(10_000L));
        assertThat(sql.getValue())
                .contains("WITH group_freshness AS")
                .contains("COALESCE(bool_and(pm.event_time IS NOT NULL")
                .contains("AND p.user_id = ?")
                .contains("AND i.settle_asset = ?")
                .contains("CROSS JOIN group_freshness gf")
                .contains("AND gf.all_marks_fresh")
                .contains("instrument_risk_brackets");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }
}
