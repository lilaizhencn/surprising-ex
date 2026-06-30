package com.surprising.price.index.repository;

import com.surprising.price.api.model.ExchangeRateResponse;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ExchangeRateRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO price_exchange_rates (
                base_currency, quote_currency, rate, provider, rate_time, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (base_currency, quote_currency) DO UPDATE SET
                rate = EXCLUDED.rate,
                provider = EXCLUDED.provider,
                rate_time = EXCLUDED.rate_time,
                updated_at = EXCLUDED.updated_at
            """;

    private final JdbcTemplate jdbcTemplate;

    public ExchangeRateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(String baseCurrency, String quoteCurrency, BigDecimal rate,
                       String provider, Instant rateTime, Instant updatedAt) {
        jdbcTemplate.update(UPSERT_SQL, baseCurrency, quoteCurrency, rate, provider,
                Timestamp.from(rateTime), Timestamp.from(updatedAt));
    }

    public Optional<ExchangeRateResponse> latest(String baseCurrency, String quoteCurrency) {
        String sql = """
                SELECT base_currency, quote_currency, rate, provider, rate_time, updated_at
                  FROM price_exchange_rates
                 WHERE base_currency = ?
                   AND quote_currency = ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ExchangeRateResponse(
                        rs.getString("base_currency"),
                        rs.getString("quote_currency"),
                        rs.getBigDecimal("rate"),
                        rs.getString("provider"),
                        rs.getTimestamp("rate_time").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                baseCurrency, quoteCurrency).stream().findFirst();
    }

    public List<ExchangeRateResponse> byBaseCurrency(String baseCurrency) {
        String sql = """
                SELECT base_currency, quote_currency, rate, provider, rate_time, updated_at
                  FROM price_exchange_rates
                 WHERE base_currency = ?
                 ORDER BY quote_currency ASC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new ExchangeRateResponse(
                        rs.getString("base_currency"),
                        rs.getString("quote_currency"),
                        rs.getBigDecimal("rate"),
                        rs.getString("provider"),
                        rs.getTimestamp("rate_time").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                baseCurrency);
    }
}
