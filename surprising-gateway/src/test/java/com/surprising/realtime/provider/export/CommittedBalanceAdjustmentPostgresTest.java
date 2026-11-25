package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.protocol.export.CommittedBalanceFlow;
import com.surprising.aeron.protocol.export.CommittedCommandResult;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.provider.export.service.CommittedOrderProjectionService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class CommittedBalanceAdjustmentPostgresTest {
    static final long TIME = 1_700_000_000_000L;
    JdbcTemplate admin, jdbc;
    String schema;
    CommittedOrderProjectionService orders;

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL"), user = System.getenv("INSTRUMENT_TEST_DB_USER"),
                password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "balance_projection_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(source);
        String init = Files.readString(Path.of("../..", "init.sql"));
        for (String table : List.of("account_balance_adjustments", "projection_watermarks",
                "trading_command_results", "portfolio_cash_flows", "trading_orders", "trading_trigger_orders",
                "trading_algo_orders", "trading_trades", "trading_command_stats",
                "trading_stats_cursors", "trading_stats_snapshots")) {
            int start = init.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
            jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
        }
        jdbc.execute("CREATE TABLE market_maker_strategies (product_line text, account_ids bigint[])");
        jdbc.execute("CREATE TABLE market_maker_business_settings (product_line text, settings jsonb)");
        orders = ProjectionTestFixtures.orders(jdbc, new DataSourceTransactionManager(source));
    }

    @AfterEach void cleanup() { if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }

    private long balanceUnits;

    @Test void originalBalanceAndTimeSurviveLaterCommandsDuplicateProjectionAndReplayRestart() {
        var first = flow(1, 100);
        var firstReceipt = receipt(1);
        seed(first, 100);
        persist(first, firstReceipt, 1);
        var second = flow(2, 25);
        seed(second, 25);
        persist(second, receipt(2), 2);
        persist(first, firstReceipt, 2);
        var row = jdbc.queryForMap("SELECT * FROM account_balance_adjustments WHERE command_id=?", first.commandId());
        assertThat((BigDecimal) row.get("balance_after_units")).isEqualByComparingTo("100");
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(Instant.ofEpochMilli(TIME + 1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_balance_adjustments", Integer.class)).isEqualTo(2);
    }

    @Test void watermarkFailureRollsBackCompletionAndReceiptAndExactRetryFinishesTheIntent() {
        var first = flow(1, 100); seed(first, 100); persist(first, receipt(1), 1);
        jdbc.execute("ALTER TABLE projection_watermarks ADD CONSTRAINT balance_watermark_fault CHECK(last_export_sequence < 2)");
        var second = flow(2, 25); var receipt = receipt(2); seed(second, 25);
        assertThatThrownBy(() -> persist(second, receipt, 2)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT created_at FROM account_balance_adjustments WHERE command_id=?", Timestamp.class,
                second.commandId())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trading_command_results", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM projection_watermarks", Long.class)).isEqualTo(1);
        jdbc.execute("ALTER TABLE projection_watermarks DROP CONSTRAINT balance_watermark_fault");
        persist(second, receipt, 2);
        assertThat(jdbc.queryForObject("SELECT balance_after_units FROM account_balance_adjustments WHERE command_id=?",
                BigDecimal.class, second.commandId())).isEqualByComparingTo("125");
    }

    @Test void conflictingRequestCannotPublishAnyCompletionReceiptStatisticsOrWatermark() {
        var flow = flow(1, 100); seed(flow, 101);
        assertThatThrownBy(() -> persist(flow, receipt(1), 1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicting");
        assertThat(jdbc.queryForObject("SELECT created_at FROM account_balance_adjustments", Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trading_command_results", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM trading_command_stats", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projection_watermarks", Integer.class)).isZero();
    }

    @Test void ordinaryRequestsKeepTheirOriginWithoutStoringUnusedAdminCompletionFacts() {
        var flow = flow(1, 100); seed(flow, 100);
        jdbc.update("UPDATE account_balance_adjustments SET admin_user_id=NULL,admin_username=NULL,reason=NULL");
        persist(flow, receipt(1), 1);
        assertThat(jdbc.queryForObject("SELECT balance_after_units FROM account_balance_adjustments", BigDecimal.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT created_at FROM account_balance_adjustments", Timestamp.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM portfolio_cash_flows", Integer.class)).isEqualTo(1);
    }

    @Test void zeroCreationIsCompletedAndWideTotalsAreNeverTruncatedInStorage() {
        var zero = flow(1, 0); seed(zero, 0); persist(zero, receipt(1), 1);
        assertThat(jdbc.queryForObject("SELECT balance_after_units FROM account_balance_adjustments", BigDecimal.class))
                .isEqualByComparingTo("0");
        var wide = new CommittedBalanceFlow(ProductLine.SPOT, 42, new UUID(54, 9), "USDT", "100",
                "9223372036854775907", 576, TIME + 9, null);
        seed(wide, 100);
        orders.persist(ProductLine.SPOT, List.of(), List.of(), 9, List.of(), List.of(), List.of(wide));
        assertThat(jdbc.queryForObject("SELECT balance_after_units FROM account_balance_adjustments WHERE command_id=?",
                BigDecimal.class, wide.commandId())).isEqualByComparingTo(wide.balanceAfterUnits());
    }

    private CommittedBalanceFlow flow(long sequence, long amount) {
        balanceUnits += amount;
        return new CommittedBalanceFlow(ProductLine.SPOT, 42, new UUID(54, sequence), "USDT",
                Long.toString(amount), Long.toString(balanceUnits), sequence * 64, TIME + sequence, null);
    }

    private CommittedCommandResult receipt(long sequence) {
        return new CommittedCommandResult(ProductLine.SPOT, 42, new UUID(54, sequence),
                CoreMessageType.ADJUST_BALANCE, null, null, ResponseStatus.APPLIED, CoreResultCode.NONE,
                sequence, sequence * 64, TIME + sequence, List.of());
    }

    private void seed(CommittedBalanceFlow flow, long amount) {
        jdbc.update("""
                INSERT INTO account_balance_adjustments(product_line,command_id,adjustment_kind,admin_user_id,
                    admin_username,user_id,asset,amount_units,reference_id,reason)
                VALUES (?,?,'BASIC',7,'admin',?,'USDT',?,?,'original')
                """, flow.product().name(), flow.commandId(), flow.userId(), amount, flow.commandId().toString());
    }

    private void persist(CommittedBalanceFlow flow, CommittedCommandResult receipt, long watermark) {
        orders.persist(flow.product(), List.of(), List.of(receipt), watermark, List.of(), List.of(), List.of(flow));
    }
}
