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
        MarkPriceEvent markPrice = markPriceCache.fresh(rate.symbol(), properties.getCalculation().getMaxMarkAge())
                .orElseThrow(() -> new IllegalStateException("fresh mark price not found for " + rate.symbol()));
        long settlementId = rate.fundingTime().toEpochMilli();
        if (settlementId <= 0) throw new IllegalArgumentException("funding time must produce a positive settlement id");
        return new CoreSettlement(settlementId, markPrice.instrumentVersion());
    }

    public Optional<FundingSettlementResponse> latestCore(String symbol) {
        return jdbcTemplate.query("""
                SELECT settlement_id, symbol, funding_rate_ppm, total_long_payment_units,
                       total_short_payment_units, position_count, command_status, occurred_at_epoch_ms
                  FROM core_funding_settlement_projection
                 WHERE product_line = ? AND symbol = ?
                 ORDER BY settlement_id DESC
                 LIMIT 1
                """, (rs, rowNum) -> {
            Instant occurredAt = Instant.ofEpochMilli(rs.getLong("occurred_at_epoch_ms"));
            return new FundingSettlementResponse(rs.getLong("settlement_id"), rs.getString("symbol"),
                    Instant.ofEpochMilli(rs.getLong("settlement_id")), rs.getLong("funding_rate_ppm"),
                    rs.getLong("total_long_payment_units"), rs.getLong("total_short_payment_units"),
                    rs.getInt("position_count"), rs.getString("command_status"), occurredAt, occurredAt);
        }, properties.getKafka().getProductLine().name(), symbol).stream().findFirst();
    }

    public record CoreSettlement(long settlementId, long instrumentVersion) {
    }
}
