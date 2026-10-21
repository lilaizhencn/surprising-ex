package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cMarket;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 负责 c2c_markets。关联 assets 仅为读取权威 scale_units 和币种交易开关，避免复制资产精度。
 * 所有关联均为 asset 唯一键的一对一读取；批量入口仅接受调用方当前有界页的市场 ID，不全表缓存。
 */
@Repository
public class C2cMarketRepository {
    private final JdbcTemplate jdbc;

    public C2cMarketRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<C2cMarket> list(boolean activeOnly) {
        return jdbc.query("""
                SELECT m.*,a.scale_units FROM c2c_markets m JOIN assets a USING(asset)
                WHERE (NOT ? OR (m.enabled AND a.listed AND a.trading_enabled)) ORDER BY m.fiat,m.asset
                """, this::read, activeOnly);
    }

    public Optional<C2cMarket> find(UUID id, boolean activeOnly) {
        return jdbc.query("""
                SELECT m.*,a.scale_units FROM c2c_markets m JOIN assets a USING(asset)
                WHERE m.market_id=? AND (NOT ? OR (m.enabled AND a.listed AND a.trading_enabled))
                """, this::read, id, activeOnly).stream().findFirst();
    }

    public List<C2cMarket> byIds(Collection<UUID> ids) {
        var distinct = new ArrayList<>(ids.stream().distinct().toList());
        if (distinct.isEmpty()) return List.of();
        return jdbc.query("SELECT m.*,a.scale_units FROM c2c_markets m JOIN assets a USING(asset) WHERE m.market_id IN ("
                        + String.join(",", Collections.nCopies(distinct.size(), "?")) + ")",
                this::read, distinct.toArray());
    }

    private C2cMarket read(ResultSet r, int n) throws SQLException {
        return new C2cMarket(r.getObject("market_id", UUID.class), r.getString("asset"), r.getLong("scale_units"),
                r.getString("fiat"), r.getInt("fiat_decimals"), r.getBoolean("enabled"), r.getBigDecimal("min_fiat"),
                r.getBigDecimal("max_fiat"), r.getBigDecimal("min_price"), r.getBigDecimal("max_price"),
                r.getInt("buyer_fee_ppm"), r.getInt("payment_window_seconds"), r.getInt("max_open_orders"),
                r.getBoolean("require_kyc"), r.getInt("vip_min_completed"), r.getLong("revision"));
    }
}
