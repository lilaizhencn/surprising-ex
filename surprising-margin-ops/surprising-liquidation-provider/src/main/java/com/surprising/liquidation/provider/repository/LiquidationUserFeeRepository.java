package com.surprising.liquidation.provider.repository;

import com.surprising.product.api.ProductLine;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 用户费率仓储，只负责 {@code trading_fee_schedules} 表。 */
@Repository
public class LiquidationUserFeeRepository {

    private final JdbcTemplate jdbcTemplate;

    public LiquidationUserFeeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<Long, UserFee> findBestActive(List<UserFeeRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return Map.of();
        }
        String values = String.join(", ", Collections.nCopies(requests.size(),
                "(?, ?, ?, ?, CAST(? AS timestamptz))"));
        List<Object> args = new ArrayList<>(requests.size() * 5);
        for (UserFeeRequest request : requests) {
            args.add(request.candidateId());
            args.add(request.userId());
            args.add(request.symbol());
            args.add(request.productLine().name());
            args.add(Timestamp.from(request.effectiveTime()));
        }
        String sql = """
                WITH requested(candidate_id, user_id, symbol, product_line, effective_time) AS (
                    VALUES %s
                )
                SELECT DISTINCT ON (r.candidate_id)
                       r.candidate_id,
                       f.maker_fee_rate_ppm,
                       f.taker_fee_rate_ppm
                  FROM requested r
                  JOIN trading_fee_schedules f
                    ON f.user_id = r.user_id
                   AND f.product_line = r.product_line
                   AND f.status = 'ACTIVE'
                   AND (f.symbol = r.symbol OR f.symbol IS NULL)
                   AND f.effective_time <= r.effective_time
                   AND (f.expire_time IS NULL OR f.expire_time > r.effective_time)
                 ORDER BY r.candidate_id,
                          CASE WHEN f.symbol = r.symbol THEN 0 ELSE 1 END,
                          CASE f.source_type
                              WHEN 'RISK_OVERRIDE' THEN 0
                              WHEN 'USER_OVERRIDE' THEN 1
                              WHEN 'PROMOTION' THEN 2
                              WHEN 'MARKET_MAKER' THEN 3
                              WHEN 'VIP' THEN 4
                              ELSE 5
                          END,
                          f.effective_time DESC,
                          f.fee_schedule_id DESC
                """.formatted(values);
        return jdbcTemplate.query(sql, (rs, rowNum) -> new UserFee(
                        rs.getLong("candidate_id"),
                        rs.getLong("maker_fee_rate_ppm"),
                        rs.getLong("taker_fee_rate_ppm")), args.toArray())
                .stream()
                .collect(Collectors.toMap(UserFee::candidateId, fee -> fee));
    }

    public record UserFeeRequest(long candidateId,
                                 long userId,
                                 String symbol,
                                 ProductLine productLine,
                                 Instant effectiveTime) {
    }

    public record UserFee(long candidateId, long makerFeeRatePpm, long takerFeeRatePpm) {
    }
}
