package com.surprising.realtime.provider.export;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class CommittedOrderProjectionPostgresTest {
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private CommittedOrderProjectionRepository repository;

    @BeforeEach void prepare() {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD")));
        admin.execute("DROP SCHEMA IF EXISTS order_projection_it CASCADE");
        admin.execute("CREATE SCHEMA order_projection_it");
        source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=order_projection_it&reWriteBatchedInserts=true",
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE core_order_projection (LIKE public.core_order_projection INCLUDING ALL)");
        jdbc.execute("CREATE TABLE core_projection_watermark (LIKE public.core_projection_watermark INCLUDING ALL)");
        jdbc.execute("CREATE TABLE market_maker_strategies (product_line text, account_ids bigint[])");
        jdbc.execute("CREATE TABLE market_maker_business_settings (product_line text, settings jsonb)");
        repository = new CommittedOrderProjectionRepository(jdbc, new DataSourceTransactionManager(source));
    }

    @AfterEach void clean() { if (jdbc != null) jdbc.execute("DROP SCHEMA order_projection_it CASCADE"); }

    @Test void idempotentRestartAndOldRevisionsCannotReplaceTerminalOrdersAcrossSixLines() {
        for (var line : ProductLine.values()) {
            repository.persist(line, List.of(frame(line, 91, "OPEN", 1)), 10);
            repository.persist(line, List.of(frame(line, 91, "FILLED", 2)), 11);
            var restarted = new CommittedOrderProjectionRepository(jdbc, new DataSourceTransactionManager(source));
            restarted.persist(line, List.of(frame(line, 91, "FILLED", 2)), 11);
            restarted.persist(line, List.of(frame(line, 91, "OPEN", 1)), 10);
            var raw = jdbc.queryForObject("SELECT raw_order_state FROM core_order_projection WHERE product_line=? AND order_id=91", byte[].class, line.name());
            assertThat(CoreStateQueryCodec.decodeOrderState(raw).status()).isEqualTo("FILLED");
            assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark WHERE product_line=?", Long.class, line.name())).isEqualTo(11);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_order_projection", Integer.class)).isEqualTo(6);
    }

    @Test void failedBatchRollsBackOrdersAndWatermarkThenCanBeRetried() {
        var line = ProductLine.LINEAR_PERPETUAL;
        repository.persist(line, List.of(frame(line, 91, "OPEN", 1)), 10);
        var batch = new java.util.ArrayList<RealtimeFrame>();
        // The first JDBC batch is already executed before the final row violates the SQL column limit.
        for (int id = 92; id < 604; id++) batch.add(frame(line, id, "FILLED", 1));
        batch.add(frame(line, 604, "X".repeat(33), 1));
        assertThatThrownBy(() -> repository.persist(line, batch, 11))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_order_projection", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark", Long.class)).isEqualTo(10);
        repository.persist(line, List.of(frame(line, 92, "FILLED", 1)), 11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_order_projection", Integer.class)).isEqualTo(2);
    }

    @Test void coalescesOnlyTheLatestVersionWithinOneSqlBatchAndRejectsCrossProductFrames() {
        var line = ProductLine.LINEAR_PERPETUAL;
        repository.persist(line, List.of(frame(line, 91, "OPEN", 1), frame(line, 91, "FILLED", 2),
                frame(line, 91, "OPEN", 1)), 10);
        assertThat(jdbc.queryForObject("SELECT status FROM core_order_projection", String.class)).isEqualTo("FILLED");
        assertThatThrownBy(() -> repository.persist(line, List.of(frame(ProductLine.SPOT, 92, "OPEN", 1)), 11))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark", Long.class)).isEqualTo(10);
    }

    @Test void onlyUserFacingMakerOrdersPersistAcrossBatchesAndRestartForEveryProduct() {
        for (var line : ProductLine.values()) {
            jdbc.update("INSERT INTO market_maker_strategies VALUES (?, ARRAY[900]::bigint[])", line.name());
            jdbc.update("INSERT INTO market_maker_business_settings VALUES (?, '{\"trade\":{\"accountIds\":[901]}}'::jsonb)", line.name());
        }
        for (var line : ProductLine.values()) {
            // Unfilled quotes and cancellations are omitted. Ordinary-user orders always remain.
            repository.persist(line, List.of(order(line, 10, 900, "OPEN", 0, 1),
                    order(line, 11, 900, "CANCELED", 0, 1), order(line, 12, 7, "CANCELED", 0, 1)), 1);
            // Different maker accounts and the same maker account both count as internal trading.
            repository.persist(line, List.of(order(line, 20, 900, "FILLED", 2, 2),
                    order(line, 21, 901, "FILLED", 2, 2), execution(line, "internal", 20, 900, true),
                    execution(line, "internal", 21, 901, false)), 2);
            repository.persist(line, List.of(order(line, 22, 900, "FILLED", 2, 2),
                    order(line, 23, 900, "FILLED", 2, 2), execution(line, "self", 22, 900, true),
                    execution(line, "self", 23, 900, false)), 3);
            // This quote first fills internally, then with a user: store its full cumulative state.
            repository.persist(line, List.of(order(line, 30, 900, "OPEN", 1, 2),
                    execution(line, "first", 30, 900, true), execution(line, "first", 31, 901, false)), 4);
            repository.persist(line, List.of(order(line, 30, 900, "OPEN", 2, 3),
                    order(line, 32, 7, "FILLED", 2, 2), execution(line, "user", 30, 900, true),
                    execution(line, "user", 32, 7, false)), 5);
            // A maker account acting as taker against a user must also be retained.
            repository.persist(line, List.of(order(line, 40, 901, "FILLED", 2, 2),
                    execution(line, "maker-taker", 40, 901, false), execution(line, "maker-taker", 41, 7, true)), 5);
            // Restart, then another internal fill and cancellation must still update the stored order.
            repository = new CommittedOrderProjectionRepository(jdbc, new DataSourceTransactionManager(source));
            repository.persist(line, List.of(order(line, 30, 900, "CANCELED", 3, 4),
                    execution(line, "last", 30, 900, true), execution(line, "last", 33, 901, false)), 6);
            repository.persist(line, List.of(order(line, 30, 900, "OPEN", 2, 3)), 5);
            assertThat(jdbc.queryForList("SELECT order_id FROM core_order_projection WHERE product_line=? ORDER BY order_id",
                    Long.class, line.name())).containsExactly(12L, 30L, 32L, 40L);
            var saved = CoreStateQueryCodec.decodeOrderState(jdbc.queryForObject(
                    "SELECT raw_order_state FROM core_order_projection WHERE product_line=? AND order_id=30", byte[].class, line.name()));
            assertThat(saved.executedQuantitySteps()).isEqualTo(3);
            assertThat(saved.status()).isEqualTo("CANCELED");
        }
    }

    @Test void incompleteCounterpartyEvidenceFailsBeforeWritingOrAdvancingWatermark() {
        jdbc.update("INSERT INTO market_maker_strategies VALUES (?, ARRAY[900]::bigint[])", ProductLine.LINEAR_PERPETUAL.name());
        repository = new CommittedOrderProjectionRepository(jdbc, new DataSourceTransactionManager(source));
        var line = ProductLine.LINEAR_PERPETUAL;
        assertThatThrownBy(() -> repository.persist(line, List.of(order(line, 30, 900, "FILLED", 2, 1),
                execution(line, "missing", 30, 900, true)), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_order_projection", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_projection_watermark", Integer.class)).isZero();
    }

    private static RealtimeFrame execution(ProductLine line, String trade, long order, long user, boolean maker) {
        var bytes = java.nio.ByteBuffer.allocate(26).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putLong(order).putLong(100).putLong(1).put((byte) (maker ? 0 : 1)).put((byte) (maker ? 1 : 0)).array();
        return new RealtimeFrame(line, RealtimeFrame.Kind.EXECUTION, user, 100, 0, 1000, 0, "604", trade, bytes);
    }

    private static RealtimeFrame order(ProductLine line, long id, long user, String status, long filled, long revision) {
        var state = new CoreOrderStateView(id, line, user, "604", CoreOrderSide.BUY, 100, 4,
                filled, 4 - filled, false, status, revision);
        return RealtimeFrameCodec.decode(RealtimeFrameCodec.encodeOrder(state, 100 + revision, 0, 1000, 0));
    }

    private static RealtimeFrame frame(ProductLine line, long id, String status, long revision) {
        var order = new CoreOrderStateView(id, line, 7, "604", CoreOrderSide.BUY, 100, 2,
                status.equals("FILLED") ? 2 : 0, status.equals("FILLED") ? 0 : 2, false, status, revision);
        return RealtimeFrameCodec.decode(RealtimeFrameCodec.encodeOrder(order, 100 + revision, 0, 1000, 0));
    }
}
