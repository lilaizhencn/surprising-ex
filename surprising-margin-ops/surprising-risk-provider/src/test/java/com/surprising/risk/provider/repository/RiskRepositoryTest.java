package com.surprising.risk.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.product.api.ProductLine;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.risk.provider.repository.RiskLiquidationCandidateRepository.LiquidationCandidateWrite;
import com.surprising.risk.provider.repository.RiskPositionSnapshotRepository.PositionSnapshotWrite;
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

    @Mock
    private InstrumentSnapshotCache snapshotCache;

    @Test
    void saveAccountSnapshotFailsWhenInsertIsSkipped() {
        RiskAccountSnapshotRepository repository = new RiskAccountSnapshotRepository(jdbcTemplate);
        when(jdbcTemplate.batchUpdate(contains("INSERT INTO risk_account_snapshots"),
                any(BatchPreparedStatementSetter.class))).thenReturn(new int[]{0});

        assertThatThrownBy(() -> repository.saveAll(List.of(new RiskAccountSnapshotResponse(
                101L, 1001L, "USDT", 10_000L, -9_000L, 1_000L,
                1_100L, 1_100_000L, RiskStatus.LIQUIDATION,
                Instant.parse("2026-07-01T00:00:00Z")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("写入风险账户快照失败");
    }

    @Test
    void savePositionSnapshotFailsWhenInsertIsSkipped() {
        RiskPositionSnapshotRepository repository = new RiskPositionSnapshotRepository(jdbcTemplate);
        when(jdbcTemplate.batchUpdate(contains("INSERT INTO risk_position_snapshots"),
                any(BatchPreparedStatementSetter.class))).thenReturn(new int[]{0});

        CalculatedPositionRisk position = new CalculatedPositionRisk(1001L, "BTC-USDT", 7L, "USDT",
                10L, 65_000L, 60_000L, 600_000L, -50_000L, 100_000L);

        assertThatThrownBy(() -> repository.saveAll(List.of(new PositionSnapshotWrite(
                101L, position, 1_100_000L, RiskStatus.LIQUIDATION,
                Instant.parse("2026-07-01T00:00:00Z")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("写入风险持仓快照失败");
    }

    @Test
    void liquidationCandidateConflictOnlyIgnoresActiveCandidateConflict() {
        RiskLiquidationCandidateRepository repository = new RiskLiquidationCandidateRepository(jdbcTemplate);
        when(jdbcTemplate.batchUpdate(contains("INSERT INTO risk_liquidation_candidates"),
                any(BatchPreparedStatementSetter.class))).thenReturn(new int[]{0});
        RiskAccountSnapshotResponse account = new RiskAccountSnapshotResponse(
                101L, 1001L, "USDT", 10_000L, -9_000L, 1_000L,
                1_100L, 1_100_000L, RiskStatus.LIQUIDATION,
                Instant.parse("2026-07-01T00:00:00Z"));
        CalculatedPositionRisk position = new CalculatedPositionRisk(1001L, "BTC-USDT", 7L, "USDT",
                10L, 65_000L, 60_000L, 600_000L, -50_000L, 100_000L);

        var insertedIds = repository.createAll(List.of(new LiquidationCandidateWrite(
                901L, account, position, 1_100_000L, account.equityUnits(),
                Instant.parse("2026-07-01T00:00:00Z"))));

        assertThat(insertedIds).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).batchUpdate(sql.capture(), any(BatchPreparedStatementSetter.class));
        assertThat(sql.getValue())
                .contains("ON CONFLICT (product_line, user_id, symbol, margin_mode, position_side)")
                .contains("WHERE status IN ('NEW', 'PROCESSING') DO NOTHING")
                .doesNotContain("ON CONFLICT DO NOTHING");
    }

    @Test
    void riskGroupsUsesAccountPositionKeysetPaginationWithoutMarkDependency() {
        when(snapshotCache.initialized(ProductLine.LINEAR_PERPETUAL)).thenReturn(true);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class))).thenReturn(List.of());
        RiskRepository repository = new RiskRepository(jdbcTemplate, new RiskProperties(), snapshotCache);

        repository.riskGroups(new RiskGroupKey(1001L, "USDT"), 200);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue())
                .contains("SELECT user_id, symbol, instrument_version")
                .contains("FROM account_positions")
                .contains("product_line = ?")
                .contains("signed_quantity_steps <> 0")
                .doesNotContain("mark_prices")
                ;
        assertThat(args.getValue()).containsExactly("LINEAR_PERPETUAL");
    }

    @Test
    void riskGroupsFilterConfiguredProductLineWhenProductTopicsAreEnabled() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductLine(ProductLine.OPTION);
        properties.getKafka().setProductTopicsEnabled(true);
        when(snapshotCache.initialized(ProductLine.OPTION)).thenReturn(true);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class))).thenReturn(List.of());
        RiskRepository repository = new RiskRepository(jdbcTemplate, properties, snapshotCache);

        repository.riskGroups(null, 50);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(), args.capture());
        assertThat(sql.getValue()).contains("product_line = ?");
        assertThat(args.getValue()).containsExactly("OPTION");
    }

    @Test
    void latestPositionsFilterConfiguredProductLineWhenProductTopicsAreEnabled() {
        RiskProperties properties = new RiskProperties();
        properties.getKafka().setProductLine(ProductLine.LINEAR_DELIVERY);
        properties.getKafka().setProductTopicsEnabled(true);
        RiskPositionSnapshotRepository repository = new RiskPositionSnapshotRepository(jdbcTemplate, properties);

        repository.latest(1001L);

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
        RiskLiquidationCandidateRepository repository =
                new RiskLiquidationCandidateRepository(jdbcTemplate, properties);

        repository.findByStatus(LiquidationCandidateStatus.NEW, 25);

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
        RiskLiquidationCandidateRepository repository =
                new RiskLiquidationCandidateRepository(jdbcTemplate, properties);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(), any(Object[].class))).thenReturn(List.of());

        repository.page(LiquidationCandidateStatus.NEW, 25, null, null);

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
    void walletBalanceUsesCoinProductAccountForInversePerpetualRiskGroup() {
        RiskRepository repository = new RiskRepository(jdbcTemplate);
        when(jdbcTemplate.query(any(String.class), anyRowMapper(),
                eq("COIN_PERPETUAL"), eq(1001L), eq("BTC"), eq("INVERSE_PERPETUAL"),
                eq(1001L), eq("BTC"),
                eq(1001L), eq("BTC"), eq(1001L), eq("BTC"))).thenReturn(List.of(123L));

        long walletBalanceUnits = repository.walletBalanceUnits(1001L, "COIN_PERPETUAL", "BTC");

        assertThat(walletBalanceUnits).isEqualTo(123L);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), anyRowMapper(),
                eq("COIN_PERPETUAL"), eq(1001L), eq("BTC"), eq("INVERSE_PERPETUAL"),
                eq(1001L), eq("BTC"),
                eq(1001L), eq("BTC"), eq(1001L), eq("BTC"));
        assertThat(sql.getValue())
                .contains("SELECT ? AS account_type")
                .contains("WHERE m.user_id = ?")
                .contains("p.product_line = ?")
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
