package com.surprising.realtime.provider.export;

import static org.assertj.core.api.Assertions.*;
import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.orchestration.CommittedTradeReplay.CommittedDeliveryPage;
import com.surprising.aeron.service.orchestration.CommittedTradeReplay.DeliveryCashFlow;
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
class CommittedDeliveryProjectionPostgresTest {
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private String schema;
    private CommittedOrderProjectionRepository repository;

    @BeforeEach void prepare() {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        assertThat(url).startsWith("jdbc:postgresql://127.0.0.1:");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD")));
        schema = "delivery_cash_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE account_product_ledger_entries (LIKE public.account_product_ledger_entries INCLUDING ALL)");
        jdbc.execute("CREATE SEQUENCE account_product_ledger_entry_seq AS BIGINT");
        repository = new CommittedOrderProjectionRepository(jdbc, new DataSourceTransactionManager(source));
    }
    @AfterEach void cleanup() { if (jdbc != null) jdbc.execute("DROP SCHEMA " + schema + " CASCADE"); }

    @ParameterizedTest
    @EnumSource(value = ProductLine.class, names = {"LINEAR_DELIVERY", "INVERSE_DELIVERY"})
    void cashIsIdempotentAndConflictingPageRollsBackEveryNewUser(ProductLine line) {
        String asset = line == ProductLine.LINEAR_DELIVERY ? "USDT" : "BTC";
        var first = page(line, List.of(new DeliveryCashFlow(1, asset, -10, 990)));
        repository.persistDelivery(first);
        long entry = jdbc.queryForObject("SELECT entry_id FROM account_product_ledger_entries", Long.class);
        repository = new CommittedOrderProjectionRepository(jdbc, new DataSourceTransactionManager(source));
        repository.persistDelivery(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_product_ledger_entries", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT entry_id FROM account_product_ledger_entries", Long.class)).isEqualTo(entry);
        assertThat(jdbc.queryForObject("SELECT account_type FROM account_product_ledger_entries", String.class)).isEqualTo(line.accountTypeCode());
        assertThat(jdbc.queryForObject("SELECT instrument_id FROM account_product_ledger_entries", String.class)).isEqualTo("73");
        var conflict = page(line, List.of(new DeliveryCashFlow(2, asset, 10, 1010), new DeliveryCashFlow(1, asset, -11, 989)));
        assertThatThrownBy(() -> repository.persistDelivery(conflict)).hasMessageContaining("conflicting committed delivery");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_product_ledger_entries", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT amount_units FROM account_product_ledger_entries", Long.class)).isEqualTo(-10);
    }

    private CommittedDeliveryPage page(ProductLine line, List<DeliveryCashFlow> flows) {
        return new CommittedDeliveryPage(line, 4096, 1_700_000_000_100L,
                new SettleInstrumentCommand(1_700_000_000_000L, "73", 110, 0, 0, 16, 0, 16), flows);
    }
}
