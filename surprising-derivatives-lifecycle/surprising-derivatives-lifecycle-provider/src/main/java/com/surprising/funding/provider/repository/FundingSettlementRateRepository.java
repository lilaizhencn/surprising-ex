package com.surprising.funding.provider.repository;

import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.provider.config.FundingProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 仅访问funding_settlement_rates，冻结首次选中的结算费率，后续预测不得替换。 */
@Repository
public class FundingSettlementRateRepository {
    private final JdbcTemplate jdbcTemplate;
    private final FundingProperties properties;

    public FundingSettlementRateRepository(JdbcTemplate jdbcTemplate, FundingProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    public FundingRateResponse reserve(FundingRateResponse rate, long settlementId) {
        // A restart, a concurrent coordinator or Kafka prediction replay must reuse the
        // exact rate chosen before the first Core page; later predictions cannot replace it.
        jdbcTemplate.update("""
                INSERT INTO funding_settlement_rates(product_line,instrument_id,settlement_id,sequence,
                  funding_time,funding_interval_hours,funding_rate_ppm,premium_rate_ppm,interest_rate_ppm,event_time)
                VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """, properties.getKafka().getProductLine().name(), rate.instrumentId(), settlementId, rate.sequence(),
                java.sql.Timestamp.from(rate.fundingTime()), rate.fundingIntervalHours(), rate.fundingRatePpm(),
                rate.premiumRatePpm(), rate.interestRatePpm(), java.sql.Timestamp.from(rate.eventTime()));
        var frozen = jdbcTemplate.queryForObject("""
                SELECT *, 'PREDICTED' AS status FROM funding_settlement_rates
                 WHERE product_line=? AND instrument_id=? AND settlement_id=?
                """, (rs, row) -> toRate(rs),
                properties.getKafka().getProductLine().name(), rate.instrumentId(), settlementId);
        return java.util.Objects.requireNonNull(frozen);
    }

    private static FundingRateResponse toRate(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FundingRateResponse(
                rs.getString("instrument_id"),
                rs.getLong("sequence"),
                rs.getLong("funding_rate_ppm"),
                rs.getLong("premium_rate_ppm"),
                rs.getLong("interest_rate_ppm"),
                rs.getTimestamp("funding_time").toInstant(),
                rs.getInt("funding_interval_hours"),
                rs.getString("status"),
                rs.getTimestamp("event_time").toInstant());
    }
}
