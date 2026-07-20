package com.surprising.risk.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.risk.provider.repository.RiskRepository.LiquidationCandidateWrite;
import com.surprising.risk.provider.repository.RiskRepository.PositionSnapshotWrite;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
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
        when(jdbcTemplate.batchUpdate(contains("INSERT INTO risk_account_snapshots"),
                any(BatchPreparedStatementSetter.class))).thenReturn(new int[]{0});

        assertThatThrownBy(() -> repository.saveAccountSnapshots(List.of(new RiskAccountSnapshotResponse(
                101L, 1001L, "USDT", 10_000L, -9_000L, 1_000L,
                1_100L, 1_100_000L, RiskStatus.LIQUIDATION,
                Instant.parse("2026-07-01T00:00:00Z")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("risk account snapshot insert");
    }

    @Test
    void savePositionSnapshotFailsWhenInsertIsSkipped() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.batchUpdate(contains("INSERT INTO risk_position_snapshots"),
                any(BatchPreparedStatementSetter.class))).thenReturn(new int[]{0});

        CalculatedPositionRisk position = new CalculatedPositionRisk(1001L, "BTC-USDT", 7L, "USDT",
                10L, 65_000L, 60_000L, 600_000L, -50_000L, 100_000L);

        assertThatThrownBy(() -> repository.savePositionSnapshots(List.of(new PositionSnapshotWrite(
                101L, position, 1_100_000L, RiskStatus.LIQUIDATION,
                Instant.parse("2026-07-01T00:00:00Z")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("risk position snapshot insert");
    }

    @Test
    void liquidationCandidateConflictOnlyIgnoresActiveCandidateConflict() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.batchUpdate(contains("INSERT INTO risk_liquidation_candidates"),
                any(BatchPreparedStatementSetter.class))).thenReturn(new int[]{0});
        RiskAccountSnapshotResponse account = new RiskAccountSnapshotResponse(
                101L, 1001L, "USDT", 10_000L, -9_000L, 1_000L,
                1_100L, 1_100_000L, RiskStatus.LIQUIDATION,
                Instant.parse("2026-07-01T00:00:00Z"));
        CalculatedPositionRisk position = new CalculatedPositionRisk(1001L, "BTC-USDT", 7L, "USDT",
                10L, 65_000L, 60_000L, 600_000L, -50_000L, 100_000L);

        var insertedIds = repository.createLiquidationCandidates(List.of(new LiquidationCandidateWrite(
                901L, account, position, 1_100_000L, account.equityUnits(),
                Instant.parse("2026-07-01T00:00:00Z"))));

        assertThat(insertedIds).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).batchUpdate(sql.capture(), any(BatchPreparedStatementSetter.class));
        assertThat(sql.getValue())
                .contains("ON CONFLICT (product_line, user_id, symbol, margin_mode, position_side) WHERE status IN ('NEW', 'PROCESSING') DO NOTHING")
                .doesNotContain("ON CONFLICT DO NOTHING");
    }

    @Test
    void riskGroupsUsesAccountPositionKeysetPaginationWithoutMarkDependency() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(),
                eq(1001L), eq(1001L), eq(1001L), eq("USDT_PERPETUAL"), eq(1001L),
                eq("USDT_PERPETUAL"), eq("USDT"), eq(200))).thenReturn(List.of());

        repository.riskGroups(new RiskGroupKey(1001L, "USDT"), 200);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq(1001L), eq(1001L), eq(1001L), eq("USDT_PERPETUAL"), eq(1001L),
                eq("USDT_PERPETUAL"), eq("USDT"), eq(200));
        assertThat(sql.getValue())
                .contains("END AS account_type")
                .contains("FROM account_positions p")
                .doesNotContain("mark_prices")
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

        repository.riskGroups(null, 50);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue()).contains("p.product_line = ?");
        assertThat(args.getValue()).containsExactly(
                "OPTION", 0L, 0L, 0L, "", 0L, "", "", 50);
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
                .contains("WHERE s.user_id = ?")
                .contains("s.product_line = ?")
                .contains("ORDER BY s.symbol ASC, s.margin_mode ASC, s.position_side ASC, s.event_time DESC");
        assertThat(args.getValue()).containsExactly(1001L, "LINEAR_DELIVERY");
    }

    @Test
    void liquidationCandidatesFilterConfiguredProductLineWhenProductTopicsAreEnabled() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        RiskRepository repository = new RiskRepository(jdbcTemplate, properties);

        repository.liquidationCandidates(LiquidationCandidateStatus.NEW, 25);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue())
                .contains("FROM risk_liquidation_candidates c")
                .contains("c.status = ?")
                .contains("c.product_line = ?")
                .contains("c.account_type = ?")
                .contains("ORDER BY c.event_time ASC");
        assertThat(args.getValue()).containsExactly("NEW", "INVERSE_DELIVERY", "COIN_DELIVERY", 25);
    }

    @Test
    void liquidationCandidatesPageFilterConfiguredProductLineWhenProductTopicsAreEnabled() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        RiskRepository repository = new RiskRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class))).thenReturn(List.of());

        repository.liquidationCandidatesPage(LiquidationCandidateStatus.NEW, 25, null, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue())
                .contains("FROM risk_liquidation_candidates c")
                .contains("c.status = ?")
                .contains("c.product_line = ?")
                .contains("c.account_type = ?")
                .contains("ORDER BY c.event_time ASC, c.candidate_id ASC");
        assertThat(args.getValue()).containsExactly("NEW", "LINEAR_DELIVERY", "USDT_DELIVERY", 26);
    }

    @Test
    void highRiskAccountsFilterConfiguredProductLineWhenProductTopicsAreEnabled() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        RiskRepository repository = new RiskRepository(jdbcTemplate, properties);

        repository.highRiskAccounts(800_000L, 50);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue())
                .contains("FROM risk_account_snapshots")
                .contains("WHERE product_line = ? AND account_type = ?")
                .contains("ORDER BY user_id ASC, account_type ASC, settle_asset ASC, event_time DESC");
        assertThat(args.getValue()).containsExactly("OPTION", "OPTION", 800_000L, 800_000L, 800_000L, 800_000L, 50);
    }

    @Test
    void highRiskAccountsPageFilterConfiguredProductLineWhenProductTopicsAreEnabled() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductLine(ProductLine.INVERSE_PERPETUAL);
        properties.getKafka().setProductTopicsEnabled(true);
        RiskRepository repository = new RiskRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class))).thenReturn(List.of());

        repository.highRiskAccountsPage(800_000L, 25, null, null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue())
                .contains("FROM risk_account_snapshots")
                .contains("WHERE product_line = ? AND account_type = ?")
                .contains("ORDER BY a.event_time DESC, a.snapshot_id DESC");
        assertThat(args.getValue()).containsExactly("INVERSE_PERPETUAL", "COIN_PERPETUAL", 800_000L, 800_000L, 800_000L, 26);
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
                .contains("o.reservation_account_type = ctx.account_type")
                .contains("WHEN ctx.account_type = 'USDT_PERPETUAL'")
                .contains("LEFT JOIN account_product_balances pb")
                .contains("LEFT JOIN account_product_deficits pd");
    }

    @SuppressWarnings("unchecked")
    private RowMapper<Object> anyRowMapper() {
        return any(RowMapper.class);
    }

}
