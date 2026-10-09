package com.surprising.realtime.provider.export;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.CommittedFundingPage;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class CommittedFundingProjectionPostgresTest {
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private CommittedOrderProjectionRepository repository;
    private String schema;
    @BeforeEach void prepare() {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD")));
        schema = "funding_projection_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        for (String table : List.of("core_funding_page_projection", "core_funding_payment_projection", "core_funding_settlement_projection"))
            jdbc.execute("CREATE TABLE " + table + " (LIKE public." + table + " INCLUDING ALL)");
        repository = new CommittedOrderProjectionRepository(jdbc, new DataSourceTransactionManager(source));
    }
    @AfterEach void clean() { if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_PERPETUAL", "INVERSE_PERPETUAL"})
    void pagesAndPaymentsAreIdempotentAcrossRestartAndOnlyCompleteIntervalIsPublished(ProductLine line) {
        var first = page(line, 4096, 0, 1, false, List.of(payment(line, 1, -10, 100)));
        var last = page(line, 8192, 1, 0, true, List.of(payment(line, 2, 10, -100)));
        repository.persistFunding(first);
        repository.persistFunding(first);
        assertThat(count("core_funding_payment_projection")).isEqualTo(1);
        assertThat(count("core_funding_settlement_projection")).isZero();
        var restarted = new CommittedOrderProjectionRepository(jdbc, new DataSourceTransactionManager(source));
        restarted.persistFunding(last);
        restarted.persistFunding(first);
        restarted.persistFunding(last);
        assertThat(count("core_funding_payment_projection")).isEqualTo(2);
        assertThat(count("core_funding_settlement_projection")).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT * FROM core_funding_settlement_projection");
        assertThat(row.get("total_long_payment_units")).isEqualTo(-100L);
        assertThat(row.get("total_short_payment_units")).isEqualTo(100L);
        assertThat(row.get("position_count")).isEqualTo(2);
        assertThat(row.get("instrument_change_id")).isNull();
        assertThat(row.get("export_sequence")).isEqualTo(8192L);
    }

    @Test void missingEarlierPageAndSqlFailureDoNotLeavePartialPaymentsOrFalseCompletedSummary() {
        var line = ProductLine.LINEAR_PERPETUAL;
        var last = page(line, 8192, 1, 0, true, List.of(payment(line, 2, 10, -100)));
        assertThatThrownBy(() -> repository.persistFunding(last)).hasMessageContaining("incomplete cursor chain");
        assertThat(count("core_funding_page_projection")).isZero();
        assertThat(count("core_funding_payment_projection")).isZero();
        var invalid = new CoreFundingPaymentView(900, 1, "1", CoreMarginMode.CROSS, CorePositionSide.NET,
                "X".repeat(21), -10, 10000, 10_000, 100);
        assertThatThrownBy(() -> repository.persistFunding(page(line, 4096, 0, 1, false, List.of(invalid))))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(count("core_funding_page_projection")).isZero();
        repository.persistFunding(page(line, 4096, 0, 1, false, List.of(payment(line, 1, -10, 100))));
        repository.persistFunding(last);
        assertThat(count("core_funding_settlement_projection")).isEqualTo(1);
    }

    @Test void conflictingPageCannotSilentlyOverwriteOrSkipCommittedHistory() {
        var line = ProductLine.LINEAR_PERPETUAL;
        repository.persistFunding(page(line, 4096, 0, 1, false, List.of(payment(line, 1, -10, 100))));
        assertThatThrownBy(() -> repository.persistFunding(page(line, 8192, 0, 1, false,
                List.of(payment(line, 1, -10, 100))))).hasMessageContaining("conflicting committed funding page");
        assertThat(count("core_funding_page_projection")).isEqualTo(1);
        assertThat(count("core_funding_payment_projection")).isEqualTo(1);
        assertThat(count("core_funding_settlement_projection")).isZero();
    }

    @Test void emptyPaymentPageStillRecordsRealCompletedZeroChargeIntervalAndProductsRemainIsolated() {
        for (var line : List.of(ProductLine.LINEAR_PERPETUAL, ProductLine.INVERSE_PERPETUAL)) {
            repository.persistFunding(new CommittedFundingPage(line, 4096, 1_700_000_000_000L,
                    new ApplyFundingCommand(900, "1", 0, 0, 1), new CoreFundingProgressView(900, true, 0, 0), "NONE", List.of()));
        }
        assertThat(count("core_funding_payment_projection")).isZero();
        assertThat(count("core_funding_settlement_projection")).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT position_count FROM core_funding_settlement_projection", Integer.class))
                .containsExactly(0, 0);
        assertThatThrownBy(() -> page(ProductLine.SPOT, 4096, 0, 0, true, List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private CommittedFundingPage page(ProductLine line, long position, long cursor, long next, boolean complete,
            List<CoreFundingPaymentView> payments) {
        return new CommittedFundingPage(line, position, 1_700_000_000_000L,
                new ApplyFundingCommand(900, "1", 10_000, cursor, 1), new CoreFundingProgressView(900, complete, next, 1), "NONE", payments);
    }
    private CoreFundingPaymentView payment(ProductLine line, long user, long quantity, long amount) {
        return new CoreFundingPaymentView(900, user, "1", CoreMarginMode.CROSS, CorePositionSide.NET,
                line == ProductLine.LINEAR_PERPETUAL ? "USDT" : "BTC", quantity, 10000, 10_000, amount);
    }
}
