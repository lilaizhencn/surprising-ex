package com.surprising.affiliate.config;

import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Same DataSource/transaction as registration; query timeout applies only to Affiliate SQL. */
@Component
public final class AffiliateDatabase {

  private final JdbcTemplate jdbc;

  public AffiliateDatabase(
    JdbcTemplate applicationJdbc,
    AffiliateProperties properties
  ) {
    jdbc = new JdbcTemplate(
      Objects.requireNonNull(applicationJdbc.getDataSource())
    );
    jdbc.setQueryTimeout(properties.getQueryTimeoutSeconds());
  }

  public JdbcTemplate jdbc() {
    return jdbc;
  }
}
