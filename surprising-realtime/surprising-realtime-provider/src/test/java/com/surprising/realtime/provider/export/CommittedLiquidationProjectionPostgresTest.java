package com.surprising.realtime.provider.export;

import com.surprising.aeron.model.CoreLiquidationState.Status;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.realtime.state.RealtimeStateCapture.LiquidationChange;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.provider.export.repository.LiquidationInitializationRepository;
import com.surprising.realtime.provider.export.service.CommittedOrderProjectionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class CommittedLiquidationProjectionPostgresTest {
    private JdbcTemplate admin, jdbc;
    private String schema;
    private CommittedOrderProjectionService orders;
    private static final ProductLine LINE = ProductLine.LINEAR_PERPETUAL;

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL"), user = System.getenv("INSTRUMENT_TEST_DB_USER"), password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "liquidation_projection_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(source);
        String init = Files.readString(Path.of("../..", "init.sql"));
        for (String table : List.of("core_liquidation_projection", "core_liquidation_projection_initialization", "core_projection_watermark")) {
            int start = init.indexOf("CREATE TABLE IF NOT EXISTS " + table + " (");
            jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
        }
        orders = ProjectionTestFixtures.orders(jdbc, new DataSourceTransactionManager(source));
    }

    @AfterEach void cleanup() { if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }

    @Test void latestFactsPreserveActualFeeAndTimeAcrossDuplicateAndOlderReplay() {
        var planned = change(LINE, 32, 0, 0, Status.PLANNED);
        var executed = change(LINE, 64, 95, 5, Status.ADL_REQUIRED);
        persist(planned, 0);
        persist(executed, 1);
        persist(executed, 1);
        persist(planned, 1);
        var row = jdbc.queryForMap("SELECT * FROM core_liquidation_projection");
        assertThat(row.get("execution_price_ticks")).isEqualTo(95L);
        assertThat(row.get("liquidation_fee_rate_ppm")).isEqualTo(100_000L);
        assertThat(row.get("liquidation_fee_units")).isEqualTo(5L);
        assertThat(row.get("cluster_position")).isEqualTo(64L);
        assertThat(row.get("updated_at_epoch_ms")).isEqualTo(1_700_000_000_064L);
        assertThat(row.get("margin_mode")).isEqualTo("ISOLATED");
        assertThat(row.get("status")).isEqualTo("ADL_REQUIRED");
    }

    @Test void samePositionConflictRollsBackWatermarkAndNeverRewritesCollectedFees() {
        persist(change(LINE, 64, 95, 5, Status.ADL_REQUIRED), 1);
        assertThatThrownBy(() -> persist(change(LINE, 64, 95, 6, Status.ADL_REQUIRED), 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicting");
        assertThat(jdbc.queryForObject("SELECT liquidation_fee_units FROM core_liquidation_projection", Long.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark", Long.class)).isEqualTo(1);
    }

    @Test void productsShareNoRowsAndHistoryRepairDoesNotAdvanceOrderWatermark() {
        orders.repairLiquidations(LINE, List.of(change(LINE, 64, 95, 5, Status.ADL_REQUIRED)));
        orders.repairLiquidations(ProductLine.LINEAR_DELIVERY, List.of(change(ProductLine.LINEAR_DELIVERY, 64, 95, 5, Status.ADL_REQUIRED)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_liquidation_projection", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_projection_watermark", Integer.class)).isZero();
        var initialization = new LiquidationInitializationRepository(jdbc);
        assertThat(initialization.initialized(LINE)).isFalse();
        initialization.initialize(LINE);
        initialization.initialize(LINE);
        assertThat(initialization.initialized(LINE)).isTrue();
        assertThat(initialization.initialized(ProductLine.LINEAR_DELIVERY)).isFalse();
    }

    private void persist(LiquidationChange change, long watermark) {
        orders.persist(LINE, List.of(), List.of(), watermark, List.of(), List.of(change));
    }

    private LiquidationChange change(ProductLine line, long position, long price, long fee, Status status) {
        var instrument = mock(CoreInstrument.class);
        when(instrument.contractType()).thenReturn(com.surprising.instrument.api.model.ContractType.valueOf(line.contractTypeCode()));
        when(instrument.instrumentId()).thenReturn("73");
        when(instrument.instrumentIdValue()).thenReturn(73);
        when(instrument.settleAsset()).thenReturn("USDT");
        return new LiquidationChange(new LiquidationRuntime(7, 42, 0, CoreMarginMode.ISOLATED, CorePositionSide.NET,
                instrument, 1, 10, 10, status == Status.ADL_REQUIRED ? 5 : 0,
                price, price == 0 ? 0 : 100_000, fee, status, 0), position, 1_700_000_000_000L + position);
    }
}
