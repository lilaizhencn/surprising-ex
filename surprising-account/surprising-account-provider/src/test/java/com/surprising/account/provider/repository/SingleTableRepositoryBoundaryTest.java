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
    void accountInstrumentRepositoryOnlyQueriesInstrumentTable() {
        JdbcTemplate jdbcTemplate = emptyQueryJdbcTemplate();
        AccountInstrumentRepository repository = new AccountInstrumentRepository(jdbcTemplate);

        repository.findSettleAsset("BTC-USDT", 7L);

        String sql = capturedQuery(jdbcTemplate);
        assertThat(sql)
                .contains("FROM instruments")
                .doesNotContain("account_positions")
                .doesNotContain("account_position_margins");
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
