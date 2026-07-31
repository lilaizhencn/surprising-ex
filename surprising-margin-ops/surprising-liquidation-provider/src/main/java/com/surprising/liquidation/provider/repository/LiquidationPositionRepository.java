package com.surprising.liquidation.provider.repository;

import com.surprising.liquidation.provider.config.LiquidationProperties;
import com.surprising.liquidation.provider.model.ClaimedCandidate;
import com.surprising.liquidation.provider.model.LiquidationCloseState;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 强平持仓锁仓储，只负责 {@code account_positions} 表。 */
@Repository
public class LiquidationPositionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final LiquidationProperties properties;

    public LiquidationPositionRepository(JdbcTemplate jdbcTemplate, LiquidationProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties == null ? new LiquidationProperties() : properties;
    }

    public Map<Long, LiquidationCloseState> lockAll(List<ClaimedCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Map.of();
        }
        String values = String.join(", ", java.util.Collections.nCopies(candidates.size(),
                "(CAST(? AS bigint), CAST(? AS bigint), CAST(? AS text), CAST(? AS text), CAST(? AS text), "
                        + "CAST(? AS bigint), CAST(? AS text))"));
        List<Object> args = new ArrayList<>(candidates.size() * 7);
        for (ClaimedCandidate candidate : candidates) {
            args.add(candidate.candidateId());
            args.add(candidate.userId());
            args.add(candidate.symbol());
            args.add(candidate.marginMode().name());
            args.add(candidate.positionSide().name());
            args.add(candidate.instrumentVersion());
            args.add(currentProductLine().name());
        }
        return jdbcTemplate.query("""
                WITH requested(candidate_id, user_id, symbol, margin_mode, position_side, instrument_version,
                               product_line) AS (
                    VALUES %s
                )
                SELECT r.candidate_id, p.signed_quantity_steps
                  FROM requested r
                  JOIN account_positions p
                    ON p.user_id = r.user_id
                   AND p.product_line = r.product_line
                   AND p.symbol = r.symbol
                   AND p.margin_mode = r.margin_mode
                   AND p.position_side = r.position_side
                   AND p.instrument_version = r.instrument_version
                 ORDER BY r.candidate_id
                 FOR UPDATE OF p
                """.formatted(values), (rs, rowNum) -> Map.entry(
                rs.getLong("candidate_id"), new LiquidationCloseState(rs.getLong("signed_quantity_steps"))),
                args.toArray()).stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private ProductLine currentProductLine() {
        return properties.getKafka().isProductTopicsEnabled()
                ? properties.getKafka().getProductLine()
                : ProductLine.LINEAR_PERPETUAL;
    }
}
