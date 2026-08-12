package com.surprising.funding.provider.repository;

import com.surprising.funding.api.model.FundingRateResponse;
import com.surprising.funding.api.model.FundingSettlementResponse;
import com.surprising.funding.provider.config.FundingProperties;
import com.surprising.funding.provider.model.FundingPaymentCandidate;
import com.surprising.funding.provider.model.FundingPaymentCursor;
import com.surprising.funding.provider.model.FundingPaymentPage;
import com.surprising.funding.provider.model.FundingPaymentWrite;
import com.surprising.funding.provider.model.FundingSettlementWork;
import com.surprising.price.api.model.MarkPriceEvent;
import com.surprising.price.consumer.LatestMarkPriceCache;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 仅负责 funding_settlements 表。
 */
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

    public Optional<FundingSettlementWork> createOrResume(FundingRateResponse rate, Instant now) {
        Optional<FundingSettlementWork> existing = processing(rate.symbol(), rate.fundingTime());
        if (existing.isPresent()) {
            return existing;
        }
        MarkPriceEvent markPrice = requireMarkPrice(rate.symbol());
        Long settlementId = jdbcTemplate.queryForObject(
                "SELECT nextval('funding_settlement_id_seq')", Long.class);
        if (settlementId == null) {
            throw new IllegalStateException("failed to allocate funding settlement id");
        }
        jdbcTemplate.update("""
                INSERT INTO funding_settlements (
                    settlement_id, symbol, funding_time, funding_rate_ppm,
                    instrument_version, mark_price_ticks,
                    total_long_payment_units, total_short_payment_units,
                    position_count, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, 0, 0, 0, 'PROCESSING', ?, ?)
                ON CONFLICT (symbol, funding_time) DO NOTHING
                """, settlementId, rate.symbol(), Timestamp.from(rate.fundingTime()), rate.fundingRatePpm(),
                markPrice.instrumentVersion(), markPrice.markPriceTicks(), Timestamp.from(now), Timestamp.from(now));
        return processing(rate.symbol(), rate.fundingTime());
    }

    public Optional<FundingSettlementWork> lockProcessing(long settlementId) {
        return jdbcTemplate.query("""
                SELECT settlement_id, symbol, funding_time, funding_rate_ppm,
                       instrument_version, mark_price_ticks,
                       scan_user_id, scan_margin_mode, scan_position_side
                  FROM funding_settlements
                 WHERE settlement_id = ?
                   AND status = 'PROCESSING'
                 FOR UPDATE
                """, (rs, rowNum) -> toWork(rs), settlementId).stream().findFirst();
    }

    public void advancePage(long settlementId,
                            FundingPaymentPage page,
                            List<FundingPaymentWrite> writes,
                            Instant now) {
        long totalLongPaymentUnits = 0L;
        long totalShortPaymentUnits = 0L;
        for (FundingPaymentWrite write : writes) {
            FundingPaymentCandidate payment = write.payment();
            if (payment.signedQuantitySteps() > 0L) {
                totalLongPaymentUnits = Math.addExact(totalLongPaymentUnits, payment.amountUnits());
            } else {
                totalShortPaymentUnits = Math.addExact(totalShortPaymentUnits, payment.amountUnits());
            }
        }
        int paymentCount = writes.size();
        boolean completed = !page.hasMore();
        int rows = jdbcTemplate.update("""
                UPDATE funding_settlements
                   SET total_long_payment_units = total_long_payment_units + ?,
                       total_short_payment_units = total_short_payment_units + ?,
                       position_count = position_count + ?,
                       expected_payment_count = expected_payment_count + ?,
                       scan_user_id = ?,
                       scan_margin_mode = ?,
                       scan_position_side = ?,
                       scan_completed = ?,
                       status = CASE
                           WHEN NOT ? THEN 'PROCESSING'
                           WHEN rejected_payment_count > 0 THEN 'FAILED'
                           WHEN applied_payment_count = expected_payment_count + ? THEN 'COMPLETED'
                           ELSE 'WAITING_ACCOUNTS'
                       END,
                       updated_at = ?
                 WHERE settlement_id = ? AND status = 'PROCESSING'
                """, totalLongPaymentUnits, totalShortPaymentUnits, paymentCount, paymentCount,
                page.nextCursor().userId(), page.nextCursor().marginMode(), page.nextCursor().positionSide(),
                completed, completed, paymentCount, Timestamp.from(now), settlementId);
        if (rows != 1) {
            throw new IllegalStateException("failed to write funding settlement page");
        }
    }

    public Optional<FundingSettlementResponse> latest(String symbol) {
        return jdbcTemplate.query("""
                SELECT *
                  FROM funding_settlements
                 WHERE symbol = ?
                 ORDER BY funding_time DESC
                 LIMIT 1
                """, (rs, rowNum) -> new FundingSettlementResponse(
                rs.getLong("settlement_id"),
                rs.getString("symbol"),
                rs.getTimestamp("funding_time").toInstant(),
                rs.getLong("funding_rate_ppm"),
                rs.getLong("total_long_payment_units"),
                rs.getLong("total_short_payment_units"),
                rs.getInt("position_count"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()), symbol).stream().findFirst();
    }

    private Optional<FundingSettlementWork> processing(String symbol, Instant fundingTime) {
        return jdbcTemplate.query("""
                SELECT settlement_id, symbol, funding_time, funding_rate_ppm,
                       instrument_version, mark_price_ticks,
                       scan_user_id, scan_margin_mode, scan_position_side
                  FROM funding_settlements
                 WHERE symbol = ?
                   AND funding_time = ?
                   AND status = 'PROCESSING'
                """, (rs, rowNum) -> toWork(rs),
                symbol, Timestamp.from(fundingTime)).stream().findFirst();
    }

    private FundingSettlementWork toWork(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new FundingSettlementWork(
                rs.getLong("settlement_id"),
                rs.getString("symbol"),
                rs.getTimestamp("funding_time").toInstant(),
                rs.getLong("funding_rate_ppm"),
                rs.getLong("instrument_version"),
                rs.getLong("mark_price_ticks"),
                new FundingPaymentCursor(
                        rs.getLong("scan_user_id"),
                        rs.getString("scan_margin_mode"),
                        rs.getString("scan_position_side")));
    }

    private MarkPriceEvent requireMarkPrice(String symbol) {
        return markPriceCache.fresh(symbol, properties.getCalculation().getMaxMarkAge())
                .orElseThrow(() -> new IllegalStateException("fresh mark price not found for " + symbol));
    }
}
