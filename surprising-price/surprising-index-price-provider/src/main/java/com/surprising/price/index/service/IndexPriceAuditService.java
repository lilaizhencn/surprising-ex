package com.surprising.price.index.service;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.index.repository.IndexPriceComponentRepository;
import com.surprising.price.index.repository.IndexPriceTickRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 编排指数价格审计快照的写入和清理。
 *
 * <p>清理必须在同一 SQL 中先删除明细再删除主记录，否则外键约束下会出现部分清理或并发遗漏；
 * 因此该跨表删除保留在 Service，而两个 Repository 分别只负责各自表。</p>
 */
@Service
public class IndexPriceAuditService {

    private final JdbcTemplate jdbcTemplate;
    private final IndexPriceTickRepository tickRepository;
    private final IndexPriceComponentRepository componentRepository;

    public IndexPriceAuditService(JdbcTemplate jdbcTemplate,
                                  IndexPriceTickRepository tickRepository,
                                  IndexPriceComponentRepository componentRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.tickRepository = tickRepository;
        this.componentRepository = componentRepository;
    }

    @Transactional
    public void saveBatch(List<IndexPriceEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        tickRepository.saveBatch(events);
        componentRepository.saveBatch(events);
    }

    @Transactional
    public int deleteBefore(Instant cutoff, int limit) {
        return jdbcTemplate.update("""
                WITH expired AS MATERIALIZED (
                    SELECT symbol, sequence
                      FROM price_index_ticks
                     WHERE event_time < ?
                     ORDER BY event_time ASC
                     LIMIT ?
                ), deleted_components AS (
                    DELETE FROM price_index_components c
                    USING expired e
                    WHERE c.symbol = e.symbol
                      AND c.sequence = e.sequence
                )
                DELETE FROM price_index_ticks t
                USING expired e
                WHERE t.symbol = e.symbol
                  AND t.sequence = e.sequence
                """, Timestamp.from(cutoff), limit);
    }
}
