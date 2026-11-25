package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.export.CommittedBalanceFlow;
import com.surprising.aeron.protocol.export.CommittedPortfolioSnapshot;
import com.surprising.aeron.protocol.export.CommittedPortfolioSnapshot.UserEquity;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.provider.export.service.CommittedPortfolioHistoryService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class CommittedPortfolioHistoryPostgresTest {
    private static final long NOW = 1_792_454_400_000L;
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private CommittedPortfolioHistoryService history;
    private String schema;

    @BeforeEach void prepare() throws Exception {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        String user = System.getenv("INSTRUMENT_TEST_DB_USER"), password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "portfolio_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(source);
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("deployment/migrations/20261020-portfolio-income.sql"))) root = root.getParent();
        assertNotNull(root);
        String migration = Files.readString(root.resolve("deployment/migrations/20261020-portfolio-income.sql"));
        jdbc.execute(migration);
        jdbc.execute(migration);
        String init = Files.readString(root.resolve("init.sql"));
        int adjustment = init.indexOf("CREATE TABLE IF NOT EXISTS account_balance_adjustments (");
        jdbc.execute(init.substring(adjustment, init.indexOf("\n);", adjustment) + 3));
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        history = ProjectionTestFixtures.portfolio(jdbc, transaction);
    }

    @AfterEach void clean() { if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }

    @Test void replayDeduplicatesExactFactsAndAConflictRollsBackTheEntireBatch() {
        var original = flow(1, "9007199254740993");
        transaction.executeWithoutResult(tx -> history.persist(List.of(original, original)));
        assertEquals(1, count("portfolio_cash_flows"));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(tx -> history.persist(List.of(flow(3, "3"), flow(3, "4")))));
        assertEquals(1, count("portfolio_cash_flows"));
        assertEquals(0, new BigDecimal("9007199254740993").compareTo(jdbc.queryForObject(
                "SELECT amount_units FROM portfolio_cash_flows", BigDecimal.class)));
        var conflict = flow(1, "9007199254740994");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> transaction.executeWithoutResult(tx -> history.persist(List.of(flow(2, "2"), conflict))));
        assertEquals(1, count("portfolio_cash_flows"));
    }

    @Test void userRowsAndCompletePrefixMetadataCommitOrRollbackTogether() {
        var invalid = snapshot(NOW, Map.of(-1L, new UserEquity(true, Map.of("USDT", "1"))));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> history.publish(invalid, 1, 1, 0, true));
        assertEquals(0, count("portfolio_cutoffs"));
        assertEquals(0, count("portfolio_equities"));
        var valid = snapshot(NOW, Map.of(7L, new UserEquity(true, Map.of("USDT", "18446744073709551614")),
                8L, new UserEquity(false, Map.of("USDT", "10"))));
        history.publish(valid, 10, 10, 0, false);
        history.publish(snapshot(NOW, Map.of(7L, new UserEquity(true, Map.of("USDT", "0")))), 11, 11, 0, true);
        assertEquals(1, count("portfolio_cutoffs"));
        assertEquals(2, count("portfolio_equities"));
        assertEquals("18446744073709551614", jdbc.queryForObject(
                "SELECT net_asset_units->>'USDT' FROM portfolio_equities WHERE user_id=7", String.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT complete FROM portfolio_equities WHERE user_id=8", Boolean.class));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT day_opening FROM portfolio_cutoffs", Boolean.class));
        assertEquals(10L, jdbc.queryForObject("SELECT cluster_position FROM portfolio_cutoffs", Long.class));
    }

    @Test void restartRetainsCoverageAndPruningKeepsDailyOpeningAndRecentCutsOnly() {
        long minute = NOW + 5 * 60_000;
        for (long cut : new long[]{NOW - 2 * 86_400_000, NOW, NOW + 60_000, minute - 60_000, minute})
            history.publish(snapshot(cut, Map.of(7L, new UserEquity(true, Map.of("USDT", "10")))), 1, 1, 0, cut <= NOW);
        history.publish(new CommittedPortfolioSnapshot(ProductLine.OPTION, NOW, NOW, Map.of()), 1, 1, 0, true);
        history.prune(ProductLine.SPOT, NOW, minute);
        assertTrue(history.contains(ProductLine.SPOT, NOW));
        assertTrue(history.contains(ProductLine.SPOT, minute - 60_000));
        assertTrue(history.contains(ProductLine.SPOT, minute));
        assertFalse(history.contains(ProductLine.SPOT, NOW + 60_000));
        assertFalse(history.contains(ProductLine.SPOT, NOW - 2 * 86_400_000));
        assertTrue(history.contains(ProductLine.OPTION, NOW));
    }

    @Test void fractionalAccountingUnitsAreRejectedRatherThanRoundedByNumericColumns() {
        assertThrows(NumberFormatException.class, () -> transaction.executeWithoutResult(tx -> history.persist(List.of(flow(1, "0.5")))));
        assertEquals(0, count("portfolio_cash_flows"));
    }

    @Test void transferReceiptsKeepTheirIdentityAndSurviveCashAdjustmentRetention() {
        var transfer = new com.surprising.aeron.protocol.TransferFundsCommand(91, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                "SPOT", "USDT_PERPETUAL", "USDT", 100, "synthetic-91", "", 7, 8);
        var outgoing = new CommittedBalanceFlow(ProductLine.SPOT, 7, new UUID(79, 91), "USDT", "-100", "900", 91, NOW, transfer);
        history.persist(List.of(outgoing, flow(1, "1")));
        var row = jdbc.queryForMap("SELECT source_product_line,target_product_line,source_user_id,target_user_id FROM portfolio_cash_flows WHERE transfer_id=91");
        assertEquals("SPOT", row.get("source_product_line"));
        assertEquals("LINEAR_PERPETUAL", row.get("target_product_line"));
        assertEquals(7L, row.get("source_user_id"));
        assertEquals(8L, row.get("target_user_id"));
        history.prune(ProductLine.SPOT, NOW + 3 * 86_400_000, NOW + 3 * 86_400_000);
        assertEquals(1, count("portfolio_cash_flows"));
        assertEquals(91L, jdbc.queryForObject("SELECT transfer_id FROM portfolio_cash_flows", Long.class));
    }

    @Test void aPartialTransferIdentityCannotPassTheDatabaseConstraint() {
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("""
                INSERT INTO portfolio_cash_flows
                  (product_line,command_id,user_id,cluster_position,occurred_at_epoch_ms,asset,amount_units,source_product_line)
                VALUES ('SPOT',?,7,1,?,'USDT',1,'SPOT')
                """, UUID.randomUUID(), NOW));
    }

    @Test void zeroAdjustmentAuditFactsDoNotBecomeCashFlowRows() {
        history.persist(List.of(flow(1, "0")));
        assertEquals(0, count("portfolio_cash_flows"));
    }

    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private CommittedPortfolioSnapshot snapshot(long at, Map<Long, UserEquity> users) {
        return new CommittedPortfolioSnapshot(ProductLine.SPOT, at, at, users);
    }
    private CommittedBalanceFlow flow(long id, String amount) {
        return new CommittedBalanceFlow(ProductLine.SPOT, 7, new UUID(79, id), "USDT", amount, "1000", id, NOW + id, null);
    }
}
