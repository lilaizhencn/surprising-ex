package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅访问core_projection_watermark；Service在同一事务的最后一步推进可见前缀。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class ProjectionWatermarkRepository {
    private final JdbcTemplate jdbc;

    public ProjectionWatermarkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void advanceWatermark(ProductLine product, long exportSequence) {
        jdbc.update("""
                INSERT INTO core_projection_watermark(product_line,last_export_sequence)
                VALUES (?,?) ON CONFLICT (product_line) DO UPDATE SET
                  last_export_sequence=EXCLUDED.last_export_sequence,updated_at=now()
                WHERE core_projection_watermark.last_export_sequence < EXCLUDED.last_export_sequence
                """, product.name(), exportSequence);
    }
}
