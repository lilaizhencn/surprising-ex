package com.surprising.funding.provider.repository;

import com.surprising.funding.api.model.FundingSettlementResponse;
import com.surprising.funding.provider.config.FundingProperties;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只读core_funding_settlement_projection；仅确定性提交回放能发布结算完成历史。 */
@Repository
public class FundingSettlementProjectionRepository {
    private final JdbcTemplate jdbcTemplate;
    private final FundingProperties properties;

    public FundingSettlementProjectionRepository(JdbcTemplate jdbcTemplate, FundingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public Optional<FundingSettlementResponse> latestCore(String instrumentId) {
        return jdbcTemplate.query("""
                SELECT settlement_id, instrument_id, funding_rate_ppm, total_long_payment_units,
                       total_short_payment_units, position_count, command_status, occurred_at_epoch_ms
                  FROM core_funding_settlement_projection
                 WHERE product_line = ? AND instrument_id = ?
                 ORDER BY settlement_id DESC
                 LIMIT 1
                """, (rs, rowNum) -> {
            Instant occurredAt = Instant.ofEpochMilli(rs.getLong("occurred_at_epoch_ms"));
            return new FundingSettlementResponse(rs.getLong("settlement_id"), rs.getString("instrument_id"),
                    Instant.ofEpochMilli(rs.getLong("settlement_id")), rs.getLong("funding_rate_ppm"),
                    rs.getLong("total_long_payment_units"), rs.getLong("total_short_payment_units"),
                    rs.getInt("position_count"), rs.getString("command_status"), occurredAt, occurredAt);
        }, properties.getKafka().getProductLine().name(), instrumentId).stream().findFirst();
    }
}
