package com.surprising.instrument.provider.repository;

import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.OptionalLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只负责 {@code instrument_product_current_versions} 表。 */
@Repository
public class InstrumentProductCurrentVersionRepository {

    private final JdbcTemplate jdbcTemplate;

    public InstrumentProductCurrentVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void set(ProductLine productLine, String symbol, long version, Instant now) {
        jdbcTemplate.update("""
                INSERT INTO instrument_product_current_versions (product_line, symbol, version, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (product_line, symbol) DO UPDATE SET
                    version = EXCLUDED.version,
                    updated_at = EXCLUDED.updated_at
                """, productLine.name(), symbol, version, Timestamp.from(now));
    }

    public OptionalLong findVersion(ProductLine productLine, String symbol) {
        List<Long> versions = jdbcTemplate.query("""
                SELECT version
                  FROM instrument_product_current_versions
                 WHERE product_line = ? AND symbol = ?
                """, (rs, rowNum) -> rs.getLong("version"), productLine.name(), symbol);
        return versions.isEmpty() ? OptionalLong.empty() : OptionalLong.of(versions.getFirst());
    }

    public List<InstrumentVersionKey> findAll(ProductLine productLine) {
        return jdbcTemplate.query("""
                SELECT symbol, version
                  FROM instrument_product_current_versions
                 WHERE product_line = ?
                 ORDER BY symbol
                """, (rs, rowNum) -> new InstrumentVersionKey(
                rs.getString("symbol"), rs.getLong("version")), productLine.name());
    }

    public List<InstrumentVersionKey> findAll(Collection<ProductLine> productLines) {
        if (productLines == null || productLines.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(productLines.size(), "?"));
        List<Object> args = new ArrayList<>(productLines.size());
        productLines.forEach(productLine -> args.add(productLine.name()));
        return jdbcTemplate.query("""
                SELECT symbol, version
                  FROM instrument_product_current_versions
                 WHERE product_line IN (%s)
                 ORDER BY symbol
                """.formatted(placeholders), (rs, rowNum) -> new InstrumentVersionKey(
                rs.getString("symbol"), rs.getLong("version")), args.toArray());
    }
}
