package com.surprising.realtime.provider.export;

import static org.assertj.core.api.Assertions.*;

import com.surprising.aeron.protocol.CoreTradeFeeObservation;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.provider.export.repository.CommittedFeeRepository;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(
  named = "INSTRUMENT_TEST_JDBC_URL",
  matches = ".+"
)
class CommittedFeePostgresTest {

  JdbcTemplate admin, jdbc;
  String schema;
  CommittedFeeRepository fees;
  TransactionTemplate transaction;

  @BeforeEach
  void setup() throws Exception {
    String url = System.getenv("INSTRUMENT_TEST_JDBC_URL"),
      user = System.getenv("INSTRUMENT_TEST_DB_USER"),
      password = System.getenv("INSTRUMENT_TEST_DB_PASSWORD");
    admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
    schema = "committed_fee_" + UUID.randomUUID().toString().replace("-", "");
    admin.execute("CREATE SCHEMA " + schema);
    var source = new DriverManagerDataSource(
      url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema,
      user,
      password
    );
    jdbc = new JdbcTemplate(source);
    fees = new CommittedFeeRepository(jdbc);
    transaction = new TransactionTemplate(
      new DataSourceTransactionManager(source)
    );
    jdbc.execute(
      Files.readString(
        Path.of("../../deployment/migrations/20261028-committed-trade-fees.sql")
      )
    );
  }

  @AfterEach
  void cleanup() {
    if (admin != null && schema != null) admin.execute(
      "DROP SCHEMA " + schema + " CASCADE"
    );
  }

  CoreTradeFeeObservation value(long position, long cumulative) {
    return new CoreTradeFeeObservation(
      42,
      7,
      "1",
      "USDT",
      cumulative,
      position,
      1793180000000L + position
    );
  }

  void observe(long position, long cumulative) {
    transaction.executeWithoutResult(tx ->
      fees.observe(
        ProductLine.LINEAR_PERPETUAL,
        value(position, cumulative),
        null
      )
    );
  }

  @Test
  void initialCheckpointIsABaselineAndSubsequentPositiveAndNegativeChangesAreExact() {
    transaction.executeWithoutResult(tx ->
      fees.baseline(ProductLine.LINEAR_PERPETUAL, value(100, 50))
    );
    observe(200, 57);
    observe(200, 57);
    observe(300, 55);
    observe(400, 55);
    assertThat(
      jdbc.queryForList(
        "SELECT fee_units FROM core_trade_fee_facts ORDER BY source_id",
        Long.class
      )
    ).containsExactly(7L, -2L);
    assertThatThrownBy(() -> observe(400, 56)).isInstanceOf(
      IllegalStateException.class
    );
    transaction.executeWithoutResult(tx ->
      fees.baseline(ProductLine.LINEAR_PERPETUAL, value(100, 50))
    );
    observe(500, 60);
    assertThat(
      jdbc.queryForList(
        "SELECT fee_units FROM core_trade_fee_facts ORDER BY source_id",
        Long.class
      )
    ).containsExactly(7L, -2L, 5L);
  }

  @Test
  void failureCannotAdvanceProgressWithoutTheFeeAndProductsRemainIsolated() {
    transaction.executeWithoutResult(tx -> {
      fees.observe(ProductLine.SPOT, value(100, 10), null);
      tx.setRollbackOnly();
    });
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM core_trade_fee_progress",
        Integer.class
      )
    ).isZero();
    assertThat(
      jdbc.queryForObject(
        "SELECT count(*) FROM core_trade_fee_facts",
        Integer.class
      )
    ).isZero();
    observe(100, 10);
    transaction.executeWithoutResult(tx ->
      fees.observe(ProductLine.SPOT, value(100, 20), null)
    );
    assertThat(
      jdbc.queryForObject(
        "SELECT sum(fee_units) FROM core_trade_fee_facts",
        Long.class
      )
    ).isEqualTo(30);
  }
}
