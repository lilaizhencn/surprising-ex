package com.surprising.account.provider.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.TradeParticipantRole;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.MatchTradeEvent;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class SingleTableRepositoryBoundaryTest {

    @Test
    void accountLedgerRepositoryOnlyQueriesAccountLedgerTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        AccountLedgerRepository repository = new AccountLedgerRepository(jdbcTemplate);

        repository.page(null, null, null, 100, null, null);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_ledger_entries")
                .doesNotContain("account_balances")
                .doesNotContain("account_product_ledger_entries");
    }

    @Test
    void productLedgerRepositoryOnlyQueriesProductLedgerTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        ProductLedgerRepository repository = new ProductLedgerRepository(jdbcTemplate);

        repository.page(null, null, null, null, 100, null, null);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_product_ledger_entries")
                .doesNotContain("account_balances")
                .doesNotContain("account_ledger_entries");
    }

    @Test
    void adminBalanceAdjustmentRepositoryOnlyQueriesAdjustmentTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        AdminBalanceAdjustmentRepository repository = new AdminBalanceAdjustmentRepository(jdbcTemplate);

        repository.page(null, null, null, null, null, null, 100, null, null);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_admin_balance_adjustments")
                .doesNotContain("account_balances")
                .doesNotContain("account_ledger_entries");
    }

    @Test
    void productTransferRepositoryOnlyQueriesProductTransferTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        ProductTransferRepository repository = new ProductTransferRepository(jdbcTemplate);

        repository.page(null, null, null, 100, null, null);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_product_transfers")
                .doesNotContain("account_product_balances")
                .doesNotContain("account_product_ledger_entries");
    }

    @Test
    void accountOutboxRepositoryOnlyWritesOutboxTable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);
        AccountOutboxRepository repository = new AccountOutboxRepository(jdbcTemplate);

        repository.insert("LINEAR_PERPETUAL", "POSITION", 101L, "position-topic",
                "LINEAR_PERPETUAL:1001", "POSITION_UPDATED", "{}", Instant.parse("2026-07-30T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("INSERT INTO account_outbox_events")
                .doesNotContain("account_positions")
                .doesNotContain("account_position_margins")
                .doesNotContain("instruments");
    }

    @Test
    void accountBalanceRepositoryOnlyQueriesAccountBalanceTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        AccountBalanceRepository repository = new AccountBalanceRepository(jdbcTemplate);

        repository.findByUser(1001L);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_balances")
                .doesNotContain("account_deficits")
                .doesNotContain("account_ledger_entries");
    }

    @Test
    void accountDeficitRepositoryOnlyQueriesAccountDeficitTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        AccountDeficitRepository repository = new AccountDeficitRepository(jdbcTemplate);

        repository.findByUser(1001L);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_deficits")
                .doesNotContain("account_balances")
                .doesNotContain("account_ledger_entries");
    }

    @Test
    void productBalanceRepositoryOnlyQueriesProductBalanceTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        ProductBalanceRepository repository = new ProductBalanceRepository(jdbcTemplate);

        repository.findByUser(1001L, null);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_product_balances")
                .doesNotContain("account_product_deficits")
                .doesNotContain("account_product_ledger_entries");
    }

    @Test
    void productDeficitRepositoryOnlyQueriesProductDeficitTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        ProductDeficitRepository repository = new ProductDeficitRepository(jdbcTemplate);

        repository.findByUser(1001L, null);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_product_deficits")
                .doesNotContain("account_product_balances")
                .doesNotContain("account_product_ledger_entries");
    }

    @Test
    void positionModeRepositoryOnlyQueriesPositionModeTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        PositionModeRepository repository = new PositionModeRepository(jdbcTemplate);

        repository.find(ProductLine.LINEAR_PERPETUAL, 1001L);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_position_modes")
                .doesNotContain("account_positions")
                .doesNotContain("trading_orders");
    }

    @Test
    void tradeSettlementSideRepositoryOnlyWritesSettlementSideTable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);
        TradeSettlementSideRepository repository = new TradeSettlementSideRepository(jdbcTemplate);
        MatchTradeEvent trade = mock(MatchTradeEvent.class);
        when(trade.symbol()).thenReturn("BTC-USDT");
        when(trade.tradeId()).thenReturn(9001L);
        when(trade.takerOrderId()).thenReturn(8001L);
        when(trade.takerUserId()).thenReturn(1001L);
        when(trade.makerUserId()).thenReturn(1002L);

        repository.complete(ProductLine.LINEAR_PERPETUAL, trade, TradeParticipantRole.TAKER,
                "command-1", 100L, 20L, Instant.parse("2026-07-30T00:00:00Z"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("INSERT INTO account_trade_settlement_sides")
                .doesNotContain("trading_match_trades")
                .doesNotContain("account_positions");
    }

    @Test
    void positionRepositoryOnlyQueriesPositionTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        PositionRepository repository = new PositionRepository(jdbcTemplate);

        repository.findOpenByUser(1001L, null);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_positions")
                .doesNotContain("account_position_margins")
                .doesNotContain("instruments");
    }

    @Test
    void positionMarginRepositoryOnlyQueriesPositionMarginTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        PositionMarginRepository repository = new PositionMarginRepository(jdbcTemplate);

        repository.find(ProductLine.LINEAR_PERPETUAL, 1001L, "BTC-USDT", "USDT",
                MarginMode.ISOLATED, PositionSide.NET);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_position_margins")
                .doesNotContain("account_positions")
                .doesNotContain("instruments");
    }

    @Test
    void riskPositionSnapshotRepositoryOnlyQueriesRiskSnapshotTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        RiskPositionSnapshotRepository repository = new RiskPositionSnapshotRepository(jdbcTemplate);

        repository.findLatestIsolated(1001L, "BTC-USDT", PositionSide.NET,
                java.time.Duration.ofSeconds(10));

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM risk_position_snapshots")
                .doesNotContain("account_positions")
                .doesNotContain("instruments");
    }

    @Test
    void liquidationOrderContextRepositoryOnlyQueriesLiquidationOrderTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        LiquidationOrderContextRepository repository = new LiquidationOrderContextRepository(jdbcTemplate);

        repository.findFeeContext(9001L, 1001L, "BTC-USDT");

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM liquidation_orders")
                .doesNotContain("account_ledger_entries")
                .doesNotContain("account_balances");
    }

    @Test
    void positionModeOrderRepositoriesKeepTheirOwnTableBoundary() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(any(String.class), any(Class.class), any(Object[].class)))
                .thenReturn(false);

        new PositionModeOrderRepository(jdbcTemplate)
                .existsActive(ProductLine.LINEAR_PERPETUAL, 1001L);
        new PositionModeTriggerOrderRepository(jdbcTemplate)
                .existsPending(ProductLine.LINEAR_PERPETUAL, 1001L);
        new PositionModeAlgoOrderRepository(jdbcTemplate)
                .existsActive(ProductLine.LINEAR_PERPETUAL, 1001L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(3))
                .queryForObject(sql.capture(), any(Class.class), any(Object[].class));
        assertThat(sql.getAllValues().get(0))
                .contains("FROM trading_orders")
                .doesNotContain("trading_trigger_orders")
                .doesNotContain("trading_algo_orders");
        assertThat(sql.getAllValues().get(1))
                .contains("FROM trading_trigger_orders")
                .doesNotContain("trading_orders")
                .doesNotContain("trading_algo_orders");
        assertThat(sql.getAllValues().get(2))
                .contains("FROM trading_algo_orders")
                .doesNotContain("trading_orders")
                .doesNotContain("trading_trigger_orders");
    }

    @Test
    void openInterestShardRepositoryOnlyWritesShardTable() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(any(String.class), any(Object[].class))).thenReturn(1);
        OpenInterestShardRepository repository = new OpenInterestShardRepository(jdbcTemplate);

        repository.seed(ProductLine.LINEAR_PERPETUAL, "BTC-USDT", 1,
                Instant.parse("2026-07-30T00:00:00Z"));

        String sql = capturedUpdate(jdbcTemplate);
        assertThat(sql)
                .contains("INSERT INTO trading_symbol_open_interest_shards")
                .doesNotContain("account_positions")
                .doesNotContain("account_position_margins");
    }

    @Test
    void spotOrderReservationRepositoryOnlyQueriesReservationTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        SpotOrderReservationRepository repository = new SpotOrderReservationRepository(jdbcTemplate);

        repository.lock(9001L);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM account_spot_order_reservations")
                .doesNotContain("account_product_balances")
                .doesNotContain("account_product_ledger_entries");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static JdbcTemplate emptyQueryJdbcTemplate() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        return jdbcTemplate;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String capturedQuery(JdbcTemplate jdbcTemplate) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        return sql.getValue();
    }

    private static String capturedUpdate(JdbcTemplate jdbcTemplate) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        return sql.getValue();
    }
}
