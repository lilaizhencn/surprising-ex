package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.aeron.service.orchestration.replay.CommittedTradeReplay.CommittedDeliveryPage;
import com.surprising.aeron.service.orchestration.replay.CommittedTradeReplay.DeliveryCashFlow;
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

import static org.assertj.core.api.Assertions.*;

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
    @EnumSource(value = ProductLine.class, names = {"LINEAR_DELIVERY", "INVERSE_DELIVERY", "OPTION"})
    void cashIsIdempotentAndConflictingPageRollsBackEveryNewUser(ProductLine line) {
        String asset = line == ProductLine.INVERSE_DELIVERY ? "BTC" : "USDT";
        var first = page(line, List.of(new DeliveryCashFlow(1, asset, -10, 990)));
        repository.persistDelivery(first);
        long entry = jdbc.queryForObject("SELECT entry_id FROM account_product_ledger_entries", Long.class);
        repository = new CommittedOrderProjectionRepository(jdbc, new DataSourceTransactionManager(source));
        repository.persistDelivery(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_product_ledger_entries", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT entry_id FROM account_product_ledger_entries", Long.class)).isEqualTo(entry);
        assertThat(jdbc.queryForObject("SELECT account_type FROM account_product_ledger_entries", String.class)).isEqualTo(line.accountTypeCode());
        assertThat(jdbc.queryForObject("SELECT instrument_id FROM account_product_ledger_entries", String.class)).isEqualTo("73");
        assertThat(jdbc.queryForObject("SELECT reference_type FROM account_product_ledger_entries", String.class)).isEqualTo(line == ProductLine.OPTION ? "OPTION_EXERCISE" : "DELIVERY_SETTLEMENT");
        var conflict = page(line, List.of(new DeliveryCashFlow(2, asset, 10, 1010), new DeliveryCashFlow(1, asset, -11, 989)));
        assertThatThrownBy(() -> repository.persistDelivery(conflict)).hasMessageContaining("conflicting committed delivery");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_product_ledger_entries", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT amount_units FROM account_product_ledger_entries", Long.class)).isEqualTo(-10);
    }

    @Test
    void oldOptionClassificationMigratesWithoutAnotherCashEntry() throws Exception {
        var page = page(ProductLine.OPTION, List.of(new DeliveryCashFlow(1, "USDT", 10, 1010)));
        repository.persistDelivery(page);
        long entry = jdbc.queryForObject("SELECT entry_id FROM account_product_ledger_entries", Long.class);
        jdbc.update("UPDATE account_product_ledger_entries SET reference_type='DELIVERY_SETTLEMENT',reason='交割结算'");
        var sql = java.nio.file.Files.readString(java.nio.file.Path.of("../../deployment/migrations/20261020-option-exercise-ledger.sql"));
        jdbc.execute(sql);
        jdbc.execute(sql);
        repository.persistDelivery(page);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_product_ledger_entries", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT entry_id FROM account_product_ledger_entries", Long.class)).isEqualTo(entry);
        assertThat(jdbc.queryForObject("SELECT reference_type FROM account_product_ledger_entries", String.class)).isEqualTo("OPTION_EXERCISE");
        assertThat(jdbc.queryForObject("SELECT reason FROM account_product_ledger_entries", String.class)).isEqualTo("期权行权");
        assertThat(jdbc.queryForObject("SELECT amount_units FROM account_product_ledger_entries", Long.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT balance_after_units FROM account_product_ledger_entries", Long.class)).isEqualTo(1010);
    }

    @Test
    void conflictingOldAndCorrectOptionIdentitiesBlockMigrationWithoutDeletingFacts() throws Exception {
        var page = page(ProductLine.OPTION, List.of(new DeliveryCashFlow(1, "USDT", 10, 1010)));
        repository.persistDelivery(page);
        jdbc.update("""
                INSERT INTO account_product_ledger_entries
                  (entry_id,user_id,account_type,asset,amount_units,balance_after_units,
                   reference_type,reference_id,instrument_id,reason,created_at)
                SELECT nextval('account_product_ledger_entry_seq'),user_id,account_type,asset,amount_units,balance_after_units,
                  'DELIVERY_SETTLEMENT',reference_id,instrument_id,'交割结算',created_at
                FROM account_product_ledger_entries
                """);
        var sql = java.nio.file.Files.readString(java.nio.file.Path.of("../../deployment/migrations/20261020-option-exercise-ledger.sql"));
        assertThatThrownBy(() -> jdbc.execute(sql)).hasMessageContaining("conflicting option exercise ledger identities");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_product_ledger_entries", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_product_ledger_entries WHERE reference_type='DELIVERY_SETTLEMENT'", Integer.class)).isEqualTo(1);
    }

    private CommittedDeliveryPage page(ProductLine line, List<DeliveryCashFlow> flows) {
        return new CommittedDeliveryPage(line, 4096, 1_700_000_000_100L,
                new SettleInstrumentCommand(1_700_000_000_000L, "73", 110, 0, 0, 16, 0, 16), flows);
    }
}
