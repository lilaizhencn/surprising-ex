package com.surprising.funding.provider.repository;

import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.api.model.FundingSettlementResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FundingSettlementRepository {

    private final JdbcTemplate jdbcTemplate;
    private final FundingProperties properties;
    private final LatestMarkPriceCache markPriceCache;

    public FundingSettlementRepository(JdbcTemplate jdbcTemplate,
                                       FundingProperties properties,
                                       LatestMarkPriceCache markPriceCache) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
        this.markPriceCache = markPriceCache;
    }

    public CoreSettlement reserveCore(FundingRateResponse rate) {
        MarkPriceEvent markPrice = markPriceCache.fresh(rate.instrumentId(), properties.getCalculation().getMaxMarkAge())
                .orElseThrow(() -> new IllegalStateException("fresh mark price not found for " + rate.instrumentId()));
        long settlementId = rate.fundingTime().toEpochMilli();
        if (settlementId <= 0) throw new IllegalArgumentException("funding time must produce a positive settlement id");
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
                """, (rs, row) -> FundingRateRepository.toRate(rs),
                properties.getKafka().getProductLine().name(), rate.instrumentId(), settlementId);
        return new CoreSettlement(settlementId, java.util.Objects.requireNonNull(frozen));
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

    public record CoreSettlement(long settlementId, FundingRateResponse rate) {
    }
}
