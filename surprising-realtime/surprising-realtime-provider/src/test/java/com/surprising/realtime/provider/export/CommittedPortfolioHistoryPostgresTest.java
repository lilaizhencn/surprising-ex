package com.surprising.realtime.provider.export;

import com.surprising.aeron.service.replay.CommittedPortfolioSnapshot;
import com.surprising.aeron.service.replay.CommittedPortfolioSnapshot.UserEquity;
import com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedBalanceFlow;
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
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        history = ProjectionTestFixtures.portfolio(jdbc, transaction);
    }

    @AfterEach void clean() { if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }

    @Test void replayDeduplicatesExactFactsAndAConflictRollsBackTheEntireBatch() {
        var original = flow(1, "9007199254740993");
        transaction.executeWithoutResult(tx -> history.persist(List.of(original, original)));
        assertEquals(1, count("core_portfolio_cash_flow"));
        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(tx -> history.persist(List.of(flow(3, "3"), flow(3, "4")))));
        assertEquals(1, count("core_portfolio_cash_flow"));
        assertEquals(0, new BigDecimal("9007199254740993").compareTo(jdbc.queryForObject(
                "SELECT amount_units FROM core_portfolio_cash_flow", BigDecimal.class)));
        var conflict = flow(1, "9007199254740994");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> transaction.executeWithoutResult(tx -> history.persist(List.of(flow(2, "2"), conflict))));
        assertEquals(1, count("core_portfolio_cash_flow"));
    }

    @Test void userRowsAndCompletePrefixMetadataCommitOrRollbackTogether() {
        var invalid = snapshot(NOW, Map.of(-1L, new UserEquity(true, Map.of("USDT", "1"))));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> history.publish(invalid, 1, 1, 0, true));
        assertEquals(0, count("core_portfolio_cutoff"));
        assertEquals(0, count("core_portfolio_equity"));
        var valid = snapshot(NOW, Map.of(7L, new UserEquity(true, Map.of("USDT", "18446744073709551614")),
                8L, new UserEquity(false, Map.of("USDT", "10"))));
        history.publish(valid, 10, 10, 0, false);
        history.publish(snapshot(NOW, Map.of(7L, new UserEquity(true, Map.of("USDT", "0")))), 11, 11, 0, true);
        assertEquals(1, count("core_portfolio_cutoff"));
        assertEquals(2, count("core_portfolio_equity"));
        assertEquals("18446744073709551614", jdbc.queryForObject(
                "SELECT net_asset_units->>'USDT' FROM core_portfolio_equity WHERE user_id=7", String.class));
        assertEquals(Boolean.FALSE, jdbc.queryForObject("SELECT complete FROM core_portfolio_equity WHERE user_id=8", Boolean.class));
        assertEquals(Boolean.TRUE, jdbc.queryForObject("SELECT day_opening FROM core_portfolio_cutoff", Boolean.class));
        assertEquals(10L, jdbc.queryForObject("SELECT cluster_position FROM core_portfolio_cutoff", Long.class));
    }

    @Test void restartRetainsCoverageAndPruningKeepsDailyOpeningAndRecentCutsOnly() {
        assertEquals(new com.surprising.realtime.provider.export.model.PortfolioProjectionStart(100, NOW), history.start(ProductLine.SPOT, 100, NOW));
        assertEquals(new com.surprising.realtime.provider.export.model.PortfolioProjectionStart(100, NOW), history.start(ProductLine.SPOT, 200, NOW + 1));
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
        assertEquals(0, count("core_portfolio_cash_flow"));
    }

    @Test void transferReceiptsKeepTheirIdentityAndSurviveCashAdjustmentRetention() {
        var transfer = new com.surprising.aeron.protocol.TransferFundsCommand(91, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                "SPOT", "USDT_PERPETUAL", "USDT", 100, "synthetic-91", "", 7, 8);
        var outgoing = new CommittedBalanceFlow(ProductLine.SPOT, 7, new UUID(79, 91), "USDT", "-100", 91, NOW, transfer);
        history.persist(List.of(outgoing, flow(1, "1")));
        var row = jdbc.queryForMap("SELECT source_product_line,target_product_line,source_user_id,target_user_id FROM core_portfolio_cash_flow WHERE transfer_id=91");
        assertEquals("SPOT", row.get("source_product_line"));
        assertEquals("LINEAR_PERPETUAL", row.get("target_product_line"));
        assertEquals(7L, row.get("source_user_id"));
        assertEquals(8L, row.get("target_user_id"));
        history.prune(ProductLine.SPOT, NOW + 3 * 86_400_000, NOW + 3 * 86_400_000);
        assertEquals(1, count("core_portfolio_cash_flow"));
        assertEquals(91L, jdbc.queryForObject("SELECT transfer_id FROM core_portfolio_cash_flow", Long.class));
    }

    @Test void aPartialTransferIdentityCannotPassTheDatabaseConstraint() {
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update("""
                INSERT INTO core_portfolio_cash_flow
                  (product_line,command_id,user_id,cluster_position,occurred_at_epoch_ms,asset,amount_units,source_product_line)
                VALUES ('SPOT',?,7,1,?,'USDT',1,'SPOT')
                """, UUID.randomUUID(), NOW));
    }

    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private CommittedPortfolioSnapshot snapshot(long at, Map<Long, UserEquity> users) {
        return new CommittedPortfolioSnapshot(ProductLine.SPOT, at, at, users);
    }
    private CommittedBalanceFlow flow(long id, String amount) {
        return new CommittedBalanceFlow(ProductLine.SPOT, 7, new UUID(79, id), "USDT", amount, id, NOW + id, null);
    }
}
