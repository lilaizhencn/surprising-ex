package com.surprising.realtime.provider.export.repository;

import com.surprising.product.api.ProductLine;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import com.surprising.realtime.provider.export.model.CommittedProjectionRows.FundingCursor;

/** 仅访问core_funding_page_projection；在原资金费结算身份锁内记录页并校验重放身份。 */
@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix = "surprising.trade-export", name = "enabled", havingValue = "true")
public class CommittedFundingPageRepository {
    private final JdbcTemplate jdbc;

    public CommittedFundingPageRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean insertOrVerify(com.surprising.aeron.service.orchestration.replay.CommittedTradeReplay.CommittedFundingPage page) {
        var command = page.command();
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                page.product().name() + ':' + command.instrumentId() + ':' + command.settlementId());
        int inserted = jdbc.update("""
                INSERT INTO core_funding_page_projection(product_line,cluster_position,instrument_id,
                  settlement_id,cursor_user_id,next_cursor_user_id,funding_rate_ppm,complete,occurred_at_epoch_ms)
                VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """, page.product().name(), page.clusterPosition(), command.instrumentId(), command.settlementId(),
                command.cursorUserId(), page.progress().nextCursorUserId(), command.fundingRatePpm(),
                page.progress().complete(), page.occurredAt());
        if (inserted == 0) {
            Integer matches = jdbc.queryForObject("""
                    SELECT count(*) FROM core_funding_page_projection WHERE product_line=? AND cluster_position=?
                      AND instrument_id=? AND settlement_id=? AND cursor_user_id=? AND next_cursor_user_id=?
                      AND funding_rate_ppm=? AND complete=? AND occurred_at_epoch_ms=?
                    """, Integer.class, page.product().name(), page.clusterPosition(), command.instrumentId(),
                    command.settlementId(), command.cursorUserId(), page.progress().nextCursorUserId(),
                    command.fundingRatePpm(), page.progress().complete(), page.occurredAt());
            if (matches == null || matches != 1)
                throw new IllegalStateException("conflicting committed funding page identity");
            return false;
        }
        return true;
    }

    public List<FundingCursor> cursors(com.surprising.aeron.service.orchestration.replay.CommittedTradeReplay.CommittedFundingPage page) {
        var command = page.command();
        return jdbc.query("""
            SELECT cursor_user_id,next_cursor_user_id,funding_rate_ppm,complete
            FROM core_funding_page_projection
            WHERE product_line=? AND instrument_id=? AND settlement_id=? ORDER BY cluster_position
            """, (rs, row) -> new FundingCursor(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getBoolean(4)),
            page.product().name(),command.instrumentId(),command.settlementId());
    }
}
