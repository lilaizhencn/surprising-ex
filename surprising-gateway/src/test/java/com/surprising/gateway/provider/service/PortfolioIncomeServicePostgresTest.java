package com.surprising.gateway.provider.service;

import static org.junit.jupiter.api.Assertions.*;

import com.surprising.gateway.provider.repository.PortfolioIncomeCutoffRepository;
import com.surprising.gateway.provider.repository.PortfolioIncomeFlowRepository;
import com.surprising.gateway.provider.repository.PortfolioIncomeSpotPriceRepository;
import com.surprising.asset.repository.AssetRepository;
import com.surprising.gateway.provider.model.PortfolioIncomeRows.Cut;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.PortfolioIncomePeriod;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class PortfolioIncomeServicePostgresTest {
    private static final long NOW = Instant.parse("2026-10-20T04:00:30Z").toEpochMilli();
    private static final long OPEN = PortfolioIncomePeriod.dayStart(NOW);
    private static final long CURRENT = PortfolioIncomePeriod.minuteStart(NOW);
    private JdbcTemplate jdbc;
    private PortfolioIncomeService service;
    private String schema;

    @BeforeEach void prepare() throws Exception {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        String user = System.getenv("INSTRUMENT_TEST_DB_USER"), password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "portfolio_reader_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password));
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("deployment/migrations/20261020-portfolio-income.sql"))) root = root.getParent();
        assertNotNull(root);
        jdbc.execute(Files.readString(root.resolve("deployment/migrations/20261020-portfolio-income.sql")));
        jdbc.execute("""
                CREATE TABLE assets(asset_id int PRIMARY KEY,asset text,display_name text,logo_url text,
                  scale_units bigint,listed boolean DEFAULT true,trading_enabled boolean DEFAULT true,
                  revision bigint DEFAULT 1,updated_at timestamptz DEFAULT now());
                INSERT INTO assets(asset_id,asset,scale_units) VALUES(1,'USDT',1000000),(2,'BTC',100000000);
                CREATE TABLE instruments(product_line text,instrument_id bigint,base_asset_id int,quote_asset_id int,price_tick_units bigint);
                INSERT INTO instruments VALUES('SPOT',91,2,1,10000),('LINEAR_PERPETUAL',91,2,1,100000000);
                CREATE TABLE core_execution_projection(product_line text,instrument_id text,export_sequence bigint,
                  execution_index int,price_ticks bigint,occurred_at_epoch_ms bigint);
                CREATE TABLE core_execution_projection_initialization(product_line text,snapshot_log_position bigint);
                CREATE TABLE core_projection_watermark(product_line text,last_export_sequence bigint);
                INSERT INTO core_execution_projection_initialization VALUES('SPOT',0);
                INSERT INTO core_projection_watermark VALUES('SPOT',30);
                """);
        for (var product : ProductLine.values()) {
            cut(product, OPEN, 100, 10, true);
            cut(product, CURRENT, 300, 30, false);
        }
        service = new PortfolioIncomeService(new PortfolioIncomeCutoffRepository(jdbc), new PortfolioIncomeFlowRepository(jdbc), new PortfolioIncomeSpotPriceRepository(jdbc), new AssetRepository(jdbc), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    }

    @AfterEach void clean() { if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }

    @Test
    void splitReadersKeepTheSameRepeatableReadSnapshotAcrossConcurrentBackfill() throws Exception {
        equity(ProductLine.SPOT, OPEN, 7, "USDT", "100000000");
        equity(ProductLine.SPOT, CURRENT, 7, "USDT", "100000000");
        var cutRead = new CountDownLatch(1);
        var continueReading = new CountDownLatch(1);
        var cuts = new PortfolioIncomeCutoffRepository(jdbc) {
            @Override
            public java.util.List<Cut> cuts(
                    long user, long opening, long oldest, long newest) {
                var rows = super.cuts(user, opening, oldest, newest);
                cutRead.countDown();
                try {
                    if (!continueReading.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("portfolio reader test timeout");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return rows;
            }
        };
        try (var context = new AnnotationConfigApplicationContext();
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            context.register(PortfolioReadTransactions.class);
            context.registerBean(PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(jdbc.getDataSource()));
            context.registerBean(PortfolioIncomeService.class, () -> new PortfolioIncomeService(cuts,
                    new PortfolioIncomeFlowRepository(jdbc), new PortfolioIncomeSpotPriceRepository(jdbc),
                    new AssetRepository(jdbc), Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)));
            context.refresh();
            var reader = context.getBean(PortfolioIncomeService.class);
            assertTrue(AopUtils.isAopProxy(reader));
            var future = executor.submit(() -> reader.income(7));
            try {
                assertTrue(cutRead.await(5, TimeUnit.SECONDS));
                // A concurrent historical backfill must become visible only to the next snapshot.
                flow(ProductLine.SPOT, 7, 201, OPEN + 1000, "USDT", "20000000", null, null, null, 0, 0);
            } finally {
                continueReading.countDown();
            }
            var income = future.get(10, TimeUnit.SECONDS).groups().get("overview");
            assertTrue(income.available());
            assertEquals("0", income.amountUsdt());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM core_portfolio_cash_flow", Integer.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class PortfolioReadTransactions { }

    @Test void depositsArePrincipalAndFeesAndFundingRemainIncome() {
        equity(ProductLine.SPOT, OPEN, 7, "USDT", "100000000");
        equity(ProductLine.SPOT, CURRENT, 7, "USDT", "118000000");
        flow(ProductLine.SPOT, 7, 201, OPEN + 3_600_000, "USDT", "20000000", null, null, null, 0, 0);
        var summary = service.income(7);
        var income = summary.groups().get("overview");
        assertTrue(income.available()); assertEquals("-2", income.amountUsdt());
        assertEquals("118", income.currentEquityUsdt()); assertEquals("100", income.openingEquityUsdt());
        assertEquals("-2", summary.groups().get("funding").amountUsdt());
        assertEquals("0", summary.groups().get("trading").amountUsdt());
        assertEquals("+08:00", summary.timeZone()); assertEquals(Instant.ofEpochMilli(OPEN), summary.periodStart());
        assertEquals(9, summary.groups().size());
    }

    @Test void internalTransferIsNeutralButItsIncompletePrefixCannotShowFakeProfit() {
        equity(ProductLine.SPOT, OPEN, 7, "USDT", "100000000");
        equity(ProductLine.SPOT, CURRENT, 7, "USDT", "80000000");
        equity(ProductLine.LINEAR_PERPETUAL, CURRENT, 7, "USDT", "20000000");
        flow(ProductLine.SPOT, 7, 201, OPEN + 1000, "USDT", "-20000000", 99L, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL, 7, 7);
        assertFalse(service.income(7).groups().get("overview").available());
        assertEquals("TRANSFER_PENDING", service.income(7).groups().get("overview").status());
        flow(ProductLine.LINEAR_PERPETUAL, 7, 202, OPEN + 2000, "USDT", "20000000", 99L, ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL, 7, 7);
        var summary = service.income(7);
        for (String scope : List.of("overview", "funding", "trading", "USDT_PERPETUAL")) {
            assertTrue(summary.groups().get(scope).available(), scope);
            assertEquals("0", summary.groups().get(scope).amountUsdt(), scope);
        }
    }

    @Test void crossUserC2cReceiptIsExternalCapitalAndCannotExposeAnotherUser() {
        equity(ProductLine.SPOT, CURRENT, 7, "USDT", "20000000");
        equity(ProductLine.SPOT, CURRENT, 8, "USDT", "900000000000");
        flow(ProductLine.SPOT, 7, 210, OPEN + 1000, "USDT", "20000000", 90L, ProductLine.SPOT, ProductLine.SPOT, 8, 7);
        flow(ProductLine.SPOT, 8, 211, OPEN + 1000, "USDT", "900000000000", null, null, null, 0, 0);
        assertEquals("20", service.income(7).groups().get("overview").currentEquityUsdt());
        assertEquals("0", service.income(7).groups().get("overview").amountUsdt());
        assertEquals("900000", service.income(8).groups().get("overview").currentEquityUsdt());
    }

    @Test void historicalFxValuesEveryCapitalFlowAtItsOwnCommittedPrice() {
        equity(ProductLine.SPOT, OPEN, 7, "BTC", "100000000");
        equity(ProductLine.SPOT, CURRENT, 7, "BTC", "200000000");
        long deposit = OPEN + 3_600_000;
        price("SPOT", 90, OPEN - 1, 10000);
        price("SPOT", 190, deposit, 11000);
        price("SPOT", 290, CURRENT - 1, 12000);
        price("SPOT", 299, CURRENT + 1, 99900); // a future price must never value this cut
        price("LINEAR_PERPETUAL", 299, CURRENT - 1, 77700); // duplicate instrument ID, another product
        flow(ProductLine.SPOT, 7, 201, deposit, "BTC", "100000000", null, null, null, 0, 0);
        var income = service.income(7).groups().get("overview");
        assertTrue(income.available()); assertEquals("30", income.amountUsdt());
        assertEquals("100", income.openingEquityUsdt()); assertEquals("240", income.currentEquityUsdt());
    }

    @Test void newerIncompleteCutMustNotFallBackToAnOlderZeroBalance() {
        cut(ProductLine.SPOT, CURRENT - 60_000, 250, 25, false);
        equity(ProductLine.SPOT, CURRENT, 7, "BTC", "100000000");
        assertFalse(service.income(7).groups().get("funding").available());
        assertNull(service.income(7).groups().get("funding").currentEquityUsdt());
        jdbc.update("UPDATE core_portfolio_equity SET complete=false");
        assertFalse(service.income(7).groups().get("funding").available());
    }

    @Test void missingOpeningPreservesCurrentEquityWithoutInventingIncome() {
        equity(ProductLine.SPOT, CURRENT, 7, "USDT", "120000000");
        jdbc.update("DELETE FROM core_portfolio_cutoff WHERE product_line='SPOT' AND cutoff_epoch_ms=?", OPEN);
        var income = service.income(7).groups().get("funding");
        assertFalse(income.available()); assertEquals("HISTORY_UNAVAILABLE", income.status());
        assertEquals("120", income.currentEquityUsdt()); assertNull(income.amountUsdt());
    }

    @Test void staleAndMissingProductCutsDoNotCertifyAnOverview() {
        jdbc.update("DELETE FROM core_portfolio_cutoff WHERE product_line='OPTION' AND cutoff_epoch_ms=?", CURRENT);
        assertFalse(service.income(7).groups().get("overview").available());
        assertTrue(service.income(7).groups().get("funding").available());
        jdbc.update("DELETE FROM core_portfolio_cutoff WHERE cutoff_epoch_ms=?", CURRENT);
        assertFalse(service.income(7).groups().get("funding").available());
    }

    @Test void executionCoverageComparesStateCountersRatherThanArchivePositions() {
        equity(ProductLine.SPOT, CURRENT, 7, "BTC", "100000000");
        price("SPOT", 290, CURRENT - 1, 12000);
        assertEquals("120", service.income(7).groups().get("funding").currentEquityUsdt());
        jdbc.update("UPDATE core_projection_watermark SET last_export_sequence=29");
        assertFalse(service.income(7).groups().get("funding").available());
        jdbc.update("UPDATE core_execution_projection_initialization SET snapshot_log_position=300");
        assertEquals("120", service.income(7).groups().get("funding").currentEquityUsdt());
        jdbc.update("DELETE FROM core_execution_projection_initialization");
        assertFalse(service.income(7).groups().get("funding").available());
    }

    @Test void staleFxAndUnknownOrMalformedPrecisionNeverBecomeZero() {
        equity(ProductLine.SPOT, CURRENT, 7, "BTC", "100000000");
        price("SPOT", 290, CURRENT - 121_001, 12000);
        assertFalse(service.income(7).groups().get("funding").available());
        price("SPOT", 291, CURRENT - 1, 12000);
        jdbc.update("UPDATE assets SET scale_units=3 WHERE asset='BTC'");
        assertFalse(service.income(7).groups().get("funding").available());
        jdbc.update("DELETE FROM assets WHERE asset='BTC'");
        assertFalse(service.income(7).groups().get("funding").available());
    }

    @Test void malformedUnitsAndUncoveredCashHistoryFailClosed() {
        equity(ProductLine.SPOT, CURRENT, 7, "USDT", "100000000");
        jdbc.update("UPDATE core_portfolio_equity SET net_asset_units='{\"USDT\":100}'::jsonb");
        assertFalse(service.income(7).groups().get("funding").available());
        jdbc.update("UPDATE core_portfolio_equity SET net_asset_units='{\"USDT\":\"100000000\"}'::jsonb");
        jdbc.update("UPDATE core_portfolio_cutoff SET flow_covered_from_epoch_ms=? WHERE product_line='SPOT'", OPEN + 1);
        assertEquals("HISTORY_UNAVAILABLE", service.income(7).groups().get("funding").status());
    }

    @Test void capitalFreeIncomeHasNoInventedPercentageAndExactUnitsSurviveDoubles() {
        equity(ProductLine.SPOT, CURRENT, 7, "USDT", "9007199254740993123");
        var income = service.income(7).groups().get("funding");
        assertTrue(income.available()); assertEquals("9007199254740.993123", income.amountUsdt());
        assertNull(income.percent());
        assertEquals("0", service.income(99).groups().get("overview").amountUsdt());
    }

    private void cut(ProductLine product, long time, long position, long sequence, boolean opening) {
        jdbc.update("INSERT INTO core_portfolio_cutoff VALUES(?,?,?,?,?,?,?)",
                product.name(), time, position, sequence, time - 1, 0, opening);
    }
    private void equity(ProductLine product, long time, long user, String asset, String units) {
        jdbc.update("INSERT INTO core_portfolio_equity VALUES(?,?,?,true,?::jsonb)",
                product.name(), time, user, "{\"" + asset + "\":\"" + units + "\"}");
    }
    private void price(String product, long position, long time, long ticks) {
        jdbc.update("INSERT INTO core_execution_projection VALUES(?,'91',?,0,?,?)", product, position, ticks, time);
    }
    private void flow(ProductLine product, long user, long position, long time, String asset, String amount,
                      Long transfer, ProductLine source, ProductLine target, long sourceUser, long targetUser) {
        jdbc.update("INSERT INTO core_portfolio_cash_flow VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                product.name(), UUID.randomUUID(), user, position, time, asset, new BigDecimal(amount), transfer,
                source == null ? null : source.name(), target == null ? null : target.name(),
                transfer == null ? null : sourceUser, transfer == null ? null : targetUser);
    }
}
