package com.surprising.gateway.c2c;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 市场准入、费率和内部托管身份的唯一配置来源；不缓存管理员尚未提交的修改。 */
@Repository
public class C2cMarkets {
    record Configuration(boolean enabled, long escrowUserId, long feeUserId, int maxActiveAds, long revision) { }
    record Market(UUID id, String asset, long assetScale, String fiat, int fiatDecimals, boolean enabled,
                  BigDecimal minFiat, BigDecimal maxFiat, BigDecimal minPrice, BigDecimal maxPrice,
                  int buyerFeePpm, int paymentWindowSeconds, int maxOpenOrders, boolean requireKyc,
                  int vipMinCompleted, long revision) { }
    private final JdbcTemplate jdbc;
    public C2cMarkets(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Configuration configuration() { return configuration(false); }
    Configuration configuration(boolean lock) {
        return jdbc.queryForObject("SELECT * FROM c2c_configuration WHERE singleton"+(lock?" FOR SHARE":""), (row, n) ->
                new Configuration(row.getBoolean("enabled"), row.getLong("escrow_user_id"),
                        row.getLong("fee_user_id"), row.getInt("max_active_ads"), row.getLong("revision")));
    }

    List<Market> markets(boolean activeOnly) {
        return jdbc.query("""
                SELECT m.*,a.scale_units FROM c2c_markets m JOIN assets a USING(asset)
                WHERE (NOT ? OR (m.enabled AND a.listed AND a.trading_enabled)) ORDER BY m.fiat,m.asset
                """, this::market, activeOnly);
    }

    Market market(UUID id, boolean requireActive) {
        var rows = jdbc.query("""
                SELECT m.*,a.scale_units FROM c2c_markets m JOIN assets a USING(asset)
                WHERE m.market_id=? AND (NOT ? OR (m.enabled AND a.listed AND a.trading_enabled))
                """, this::market, id, requireActive);
        if (rows.isEmpty()) throw new IllegalArgumentException("C2C 市场不存在或尚未开放");
        return rows.getFirst();
    }

    boolean validMerchantBondIntent(C2cFundsRepository.Intent intent) {
        boolean reserve=intent.purpose().equals("MERCHANT_BOND");
        long escrow=configuration().escrowUserId();
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_merchant_bonds WHERE bond_id=? AND asset=? AND amount_units=? AND user_id=?)
                """,Boolean.class,intent.ownerId(),intent.asset(),intent.amountUnits(),reserve?intent.sourceUserId():intent.targetUserId()))
                && (reserve?intent.targetUserId():intent.sourceUserId())==escrow;
    }

    void requireMerchantBond(long userId) {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_merchant_bonds b JOIN c2c_merchant_bond_policy p
                    ON p.asset=b.asset AND b.amount_units>=p.amount_units
                    WHERE b.user_id=? AND b.status='LOCKED')
                """,Boolean.class,userId))) throw new IllegalStateException("商家质押尚未确认，请先完成质押");
    }

    void requireOpen() {
        if (!configuration().enabled()) throw new IllegalStateException("C2C 暂未开放");
    }

    private Market market(java.sql.ResultSet r, int n) throws java.sql.SQLException {
        return new Market(r.getObject("market_id", UUID.class), r.getString("asset"), r.getLong("scale_units"),
                r.getString("fiat"),r.getInt("fiat_decimals"),r.getBoolean("enabled"),r.getBigDecimal("min_fiat"),
                r.getBigDecimal("max_fiat"),r.getBigDecimal("min_price"),r.getBigDecimal("max_price"),
                r.getInt("buyer_fee_ppm"),r.getInt("payment_window_seconds"),r.getInt("max_open_orders"),
                r.getBoolean("require_kyc"),r.getInt("vip_min_completed"),r.getLong("revision"));
    }
}
