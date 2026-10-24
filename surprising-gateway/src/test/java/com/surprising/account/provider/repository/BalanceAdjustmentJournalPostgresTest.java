package com.surprising.account.provider.repository;

import com.surprising.account.provider.model.AccountHistoryPageQuery;
import com.surprising.product.api.ProductLine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class BalanceAdjustmentJournalPostgresTest {
    JdbcTemplate admin, jdbc;
    String schema;
    AnnotationConfigApplicationContext context;
    BalanceAdjustmentRepository journal;
    PlatformTransactionManager manager;
    final UUID command = new UUID(54, 1);

    @Configuration(proxyBeanMethods = false) @EnableTransactionManagement
    static class Transactions {}

    @BeforeEach void setup() throws Exception {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL"), user = System.getenv("INSTRUMENT_TEST_DB_USER"),
                password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        schema = "balance_journal_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        var source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, user, password);
        jdbc = new JdbcTemplate(source); manager = new DataSourceTransactionManager(source);
        String init = Files.readString(Path.of("../init.sql"));
        int start = init.indexOf("CREATE TABLE IF NOT EXISTS account_balance_adjustments (");
        jdbc.execute(init.substring(start, init.indexOf("\n);", start) + 3));
        context = new AnnotationConfigApplicationContext();
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        context.registerBean("transactionManager", PlatformTransactionManager.class, () -> manager);
        context.register(Transactions.class, BalanceAdjustmentRepository.class);
        context.refresh(); journal = context.getBean(BalanceAdjustmentRepository.class);
    }

    @AfterEach void cleanup() {
        if (context != null) context.close();
        if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
    }

    @Test void callerRollbackCannotDiscardThePreOfferIntent() {
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            prepare(ProductLine.SPOT, 7L, "first", 100);
            tx.setRollbackOnly();
        });
        var row = jdbc.queryForMap("SELECT * FROM account_balance_adjustments");
        assertThat(row.get("admin_user_id")).isEqualTo(7L);
        assertThat(row.get("created_at")).isNull();
        assertThat(row.get("balance_after_units")).isNull();
        assertThat(journal.page(ProductLine.SPOT, null, null, null, null, null, null,
                new AccountHistoryPageQuery(10, null, true)).items()).isEmpty();
    }

    @Test void retriesKeepTheFirstActorAndRejectAChangedMonetaryRequest() {
        prepare(ProductLine.SPOT, 7L, "first", 100);
        prepare(ProductLine.SPOT, 8L, "later", 100);
        assertThatThrownBy(() -> prepare(ProductLine.SPOT, 8L, "later", 101))
                .isInstanceOfSatisfying(com.surprising.account.provider.exception.AccountCommandRejectedException.class,
                        conflict -> assertThat(conflict.errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT"))
                .hasMessageContaining("conflicting");
        var row = jdbc.queryForMap("SELECT * FROM account_balance_adjustments");
        assertThat(row.get("admin_user_id")).isEqualTo(7L);
        assertThat(row.get("admin_username")).isEqualTo("first");
        assertThat(row.get("amount_units")).isEqualTo(100L);
    }

    @Test void ordinaryOriginCannotBeReattributedToAnAdminAndProductsRemainSeparate() {
        prepare(ProductLine.SPOT, null, null, 0);
        prepare(ProductLine.SPOT, 7L, "later", 0);
        prepare(ProductLine.LINEAR_PERPETUAL, 7L, "other product", 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account_balance_adjustments", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT admin_user_id FROM account_balance_adjustments WHERE product_line='SPOT'", Long.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT reason FROM account_balance_adjustments WHERE product_line='SPOT'", String.class)).isNull();
        jdbc.update("UPDATE account_balance_adjustments SET balance_after_units=0, created_at=?",
                java.sql.Timestamp.from(java.time.Instant.parse("2026-10-24T00:00:00Z")));
        assertThat(journal.page(ProductLine.SPOT, null, null, null, null, null, null,
                new AccountHistoryPageQuery(10, null, true)).items()).isEmpty();
        var rows = journal.page(ProductLine.LINEAR_PERPETUAL, null, null, null, null, null, null,
                new AccountHistoryPageQuery(10, null, true)).items();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().accountType()).isNull(); // BASIC 未把产品账户字段重复落盘。
    }

    private void prepare(ProductLine product, Long actor, String name, long amount) {
        journal.prepare(product, command, "BASIC", actor, name, 42, "USDT", amount, "ref-1", "reason");
    }
}
