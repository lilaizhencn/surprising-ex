package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.replay.CommittedTradeReplay;
import com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedBalanceFlow;
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
        for (String table : List.of("account_balance_adjustments", "core_projection_watermark",
                "core_command_result_projection", "core_portfolio_cash_flow", "core_order_projection", "core_trigger_order_projection",
                "core_algo_order_projection", "core_execution_projection", "core_trading_command_statistics",
                "core_trading_statistics_cursor", "core_trading_statistics_initialization", "core_trading_statistics_snapshot")) {
            int start = init.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
            jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
        }
        jdbc.execute("CREATE TABLE market_maker_strategies (product_line text, account_ids bigint[])");
        jdbc.execute("CREATE TABLE market_maker_business_settings (product_line text, settings jsonb)");
        orders = ProjectionTestFixtures.orders(jdbc, new DataSourceTransactionManager(source));
    }

    @AfterEach void cleanup() { if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }

    @Test void originalBalanceAndTimeSurviveLaterCommandsDuplicateProjectionAndReplayRestart() {
        CommittedBalanceFlow first;
        CommittedTradeReplay.CommittedCommandResult firstReceipt;
        byte[] checkpoint;
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            apply(replay, 1, 100);
            first = replay.balanceFlow(); firstReceipt = replay.commandResult(); seed(first, 100);
            persist(first, firstReceipt, 1);
            apply(replay, 2, 25);
            var second = replay.balanceFlow(); seed(second, 25);
            persist(second, replay.commandResult(), 2);
            persist(first, firstReceipt, 2);
            checkpoint = replay.snapshot();
        }
        var row = jdbc.queryForMap("SELECT * FROM account_balance_adjustments WHERE command_id=?", first.commandId());
        assertThat((BigDecimal) row.get("balance_after_units")).isEqualByComparingTo("100");
        assertThat(((Timestamp) row.get("created_at")).toInstant()).isEqualTo(Instant.ofEpochMilli(TIME + 1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_balance_adjustments", Integer.class)).isEqualTo(2);
        try (var restored = new CommittedTradeReplay(ProductLine.SPOT, checkpoint)) {
            var retry = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, first.commandId(),
                    ProductLine.SPOT, CommandSource.GATEWAY, 54, 3, 42, TIME + 3, 3),
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100)));
            restored.apply(CoreMessageCodec.encode(retry), TIME + 3, 192);
            assertThat(restored.balanceFlow()).isNull();
        }
    }

    @Test void watermarkFailureRollsBackCompletionAndReceiptAndExactRetryFinishesTheIntent() {
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            apply(replay, 1, 100); var first = replay.balanceFlow(); seed(first, 100); persist(first, replay.commandResult(), 1);
            jdbc.execute("ALTER TABLE core_projection_watermark ADD CONSTRAINT balance_watermark_fault CHECK(last_export_sequence < 2)");
            apply(replay, 2, 25); var second = replay.balanceFlow(); var receipt = replay.commandResult(); seed(second, 25);
            assertThatThrownBy(() -> persist(second, receipt, 2)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT created_at FROM account_balance_adjustments WHERE command_id=?", Timestamp.class,
                    second.commandId())).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM core_command_result_projection", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark", Long.class)).isEqualTo(1);
            jdbc.execute("ALTER TABLE core_projection_watermark DROP CONSTRAINT balance_watermark_fault");
            persist(second, receipt, 2);
            assertThat(jdbc.queryForObject("SELECT balance_after_units FROM account_balance_adjustments WHERE command_id=?",
                    BigDecimal.class, second.commandId())).isEqualByComparingTo("125");
        }
    }

    @Test void conflictingRequestCannotPublishAnyCompletionReceiptStatisticsOrWatermark() {
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            apply(replay, 1, 100); var flow = replay.balanceFlow(); seed(flow, 101);
            assertThatThrownBy(() -> persist(flow, replay.commandResult(), 1))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicting");
            assertThat(jdbc.queryForObject("SELECT created_at FROM account_balance_adjustments", Timestamp.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM core_command_result_projection", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM core_trading_command_statistics", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM core_projection_watermark", Integer.class)).isZero();
        }
    }

    @Test void ordinaryRequestsKeepTheirOriginWithoutStoringUnusedAdminCompletionFacts() {
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            apply(replay, 1, 100); var flow = replay.balanceFlow(); seed(flow, 100);
            jdbc.update("UPDATE account_balance_adjustments SET admin_user_id=NULL,admin_username=NULL,reason=NULL");
            persist(flow, replay.commandResult(), 1);
            assertThat(jdbc.queryForObject("SELECT balance_after_units FROM account_balance_adjustments", BigDecimal.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT created_at FROM account_balance_adjustments", Timestamp.class)).isNull();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM core_portfolio_cash_flow", Integer.class)).isEqualTo(1);
        }
    }

    @Test void zeroCreationIsCompletedAndWideTotalsAreNeverTruncatedInStorage() {
        try (var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
            apply(replay, 1, 0); var zero = replay.balanceFlow(); seed(zero, 0); persist(zero, replay.commandResult(), 1);
            assertThat(jdbc.queryForObject("SELECT balance_after_units FROM account_balance_adjustments", BigDecimal.class))
                    .isEqualByComparingTo("0");
        }
        var wide = new CommittedBalanceFlow(ProductLine.SPOT, 42, new UUID(54, 9), "USDT", "100",
                "9223372036854775907", 576, TIME + 9, null);
        seed(wide, 100);
        orders.persist(ProductLine.SPOT, List.of(), List.of(), 9, List.of(), List.of(), List.of(wide));
        assertThat(jdbc.queryForObject("SELECT balance_after_units FROM account_balance_adjustments WHERE command_id=?",
                BigDecimal.class, wide.commandId())).isEqualByComparingTo(wide.balanceAfterUnits());
    }

    private void apply(CommittedTradeReplay replay, long sequence, long amount) {
        var command = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE, new UUID(54, sequence),
                ProductLine.SPOT, CommandSource.GATEWAY, 54, sequence, 42, TIME + sequence, sequence),
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", amount)));
        replay.apply(CoreMessageCodec.encode(command), TIME + sequence, sequence * 64);
        assertThat(replay.commandResult().status()).isEqualTo(ResponseStatus.APPLIED);
    }

    private void seed(CommittedBalanceFlow flow, long amount) {
        jdbc.update("""
                INSERT INTO account_balance_adjustments(product_line,command_id,adjustment_kind,admin_user_id,
                    admin_username,user_id,asset,amount_units,reference_id,reason)
                VALUES (?,?,'BASIC',7,'admin',?,'USDT',?,?,'original')
                """, flow.product().name(), flow.commandId(), flow.userId(), amount, flow.commandId().toString());
    }

    private void persist(CommittedBalanceFlow flow, CommittedTradeReplay.CommittedCommandResult receipt, long watermark) {
        orders.persist(flow.product(), List.of(), List.of(receipt), watermark, List.of(), List.of(), List.of(flow));
    }
}
