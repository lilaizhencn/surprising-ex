package com.surprising.gateway.provider.repository;

import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.PortfolioIncomePeriod;
import java.math.BigInteger;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/** Reads committed analysis prefixes only; never supplies spendable account balances. */
@Repository
public class PortfolioIncomeRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public PortfolioIncomeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Cut> cuts(long userId, long opening, long oldestCurrent, long newestCurrent) {
        return jdbc.query("""
                SELECT c.*,e.user_id AS account_user,e.complete,e.net_asset_units::text
                  FROM core_portfolio_cutoff c LEFT JOIN core_portfolio_equity e
                    ON e.product_line=c.product_line AND e.cutoff_epoch_ms=c.cutoff_epoch_ms AND e.user_id=?
                 WHERE (c.cutoff_epoch_ms=? OR c.cutoff_epoch_ms>=?) AND c.cutoff_epoch_ms<=?
                """, (rs, row) -> {
            boolean absent = rs.getObject("account_user") == null;
            // A cut certifies the entire user set. Absence in that complete
            // prefix proves zero; absence of the cut itself proves nothing.
            var units = absent ? Map.<String, BigInteger>of() : units(rs.getString("net_asset_units"));
            return new Cut(ProductLine.valueOf(rs.getString("product_line")), rs.getLong("cutoff_epoch_ms"),
                    rs.getLong("cluster_position"), rs.getLong("state_sequence"), rs.getLong("flow_covered_from_epoch_ms"),
                    rs.getBoolean("day_opening"), units != null && (absent || rs.getBoolean("complete")),
                    units == null ? Map.of() : units);
        }, userId, opening, oldestCurrent, newestCurrent);
    }

    public List<Flow> flows(long userId, long opening) {
        return jdbc.query("""
                SELECT * FROM core_portfolio_cash_flow WHERE user_id=?
                  AND (occurred_at_epoch_ms>=? OR (transfer_id IS NOT NULL AND source_user_id=? AND target_user_id=?))
                ORDER BY product_line,cluster_position
                """, (rs, row) -> {
            Long transfer = (Long) rs.getObject("transfer_id");
            return new Flow(ProductLine.valueOf(rs.getString("product_line")), rs.getLong("cluster_position"),
                    rs.getLong("occurred_at_epoch_ms"), rs.getString("asset"), rs.getBigDecimal("amount_units").toBigIntegerExact(),
                    transfer, transfer == null ? null : ProductLine.valueOf(rs.getString("source_product_line")),
                    transfer == null ? null : ProductLine.valueOf(rs.getString("target_product_line")),
                    rs.getLong("source_user_id"), rs.getLong("target_user_id"), rs.getObject("command_id").toString());
        }, userId, opening, userId, userId);
    }

    public Optional<Price> spotPrice(String asset, long time, Cut spotCut) {
        if (spotCut == null || spotCut.product() != ProductLine.SPOT) return Optional.empty();
        return jdbc.query("""
                SELECT e.price_ticks,i.price_tick_units,q.scale_units,e.occurred_at_epoch_ms
                  FROM core_execution_projection e JOIN instruments i
                    ON i.product_line='SPOT' AND e.product_line='SPOT' AND e.instrument_id=i.instrument_id::text
                  JOIN assets b ON b.asset_id=i.base_asset_id JOIN assets q ON q.asset_id=i.quote_asset_id
                 WHERE b.asset=? AND q.asset='USDT' AND e.price_ticks>0 AND i.price_tick_units>0 AND q.scale_units>0
                   AND e.occurred_at_epoch_ms>=? AND e.occurred_at_epoch_ms<=? AND e.export_sequence<=?
                   AND EXISTS(SELECT 1 FROM core_execution_projection_initialization init
                     LEFT JOIN core_projection_watermark w ON w.product_line=init.product_line
                     WHERE init.product_line='SPOT' AND (init.snapshot_log_position>=?
                       OR COALESCE(w.last_export_sequence,-1)>=?))
                 ORDER BY e.occurred_at_epoch_ms DESC,e.export_sequence DESC,e.execution_index DESC LIMIT 1
                """, (rs, row) -> new Price(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                asset, time - PortfolioIncomePeriod.MAXIMUM_MARK_AGE_MILLIS, time, spotCut.position(),
                spotCut.position(), spotCut.stateSequence()).stream().findFirst();
    }

    private Map<String, BigInteger> units(String text) {
        try {
            Map<?, ?> raw = json.readValue(text, Map.class);
            var units = new TreeMap<String, BigInteger>();
            for (var entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String asset) || !asset.matches("[A-Z0-9]{2,20}")
                        || !(entry.getValue() instanceof String value) || !value.matches("-?[0-9]{1,40}")) return null;
                units.put(asset, new BigInteger(value));
            }
            return Map.copyOf(units);
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    public record Cut(ProductLine product, long time, long position, long stateSequence, long coveredFrom,
                      boolean opening, boolean complete, Map<String, BigInteger> units) {}
    public record Flow(ProductLine product, long position, long time, String asset, BigInteger units,
                       Long transferId, ProductLine sourceProduct, ProductLine targetProduct,
                       long sourceUser, long targetUser, String commandId) {}
    public record Price(long ticks, long tickUnits, long quoteScaleUnits, long occurredAt) {}
}
