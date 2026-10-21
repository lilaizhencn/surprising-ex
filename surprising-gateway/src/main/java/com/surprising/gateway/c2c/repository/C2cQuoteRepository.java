package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cAd;
import com.surprising.gateway.c2c.model.C2cMarket;
import com.surprising.gateway.c2c.model.C2cPaymentAccount;
import com.surprising.gateway.c2c.model.C2cQuote;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问 c2c_quotes；有效时长由业务指定，过期时间仍以数据库时钟计算。 */
@Repository
public class C2cQuoteRepository {
    private final JdbcTemplate jdbc;

    public C2cQuoteRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public C2cQuote get(UUID id, boolean lock) {
        var rows = jdbc.query("SELECT * FROM c2c_quotes WHERE quote_id=?" + (lock ? " FOR UPDATE" : ""), this::read, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("报价不存在或已过期");
        return rows.getFirst();
    }

    public void insert(UUID id, long userId, C2cAd ad, C2cMarket market, long units, long fee,
                       BigDecimal fiat, C2cPaymentAccount account, int validSeconds) {
        jdbc.update("""
                INSERT INTO c2c_quotes(quote_id,user_id,ad_id,ad_revision,market_revision,quantity_units,buyer_fee_units,
                    fiat_amount,price,payment_account_id,payment_account_revision,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,now()+?*interval '1 second')
                """, id, userId, ad.id(), ad.revision(), market.revision(), units, fee, fiat, ad.price(),
                account.id(), account.revision(), validSeconds);
    }

    private C2cQuote read(ResultSet r, int n) throws SQLException {
        return new C2cQuote(r.getObject("quote_id", UUID.class), r.getLong("user_id"), r.getObject("ad_id", UUID.class),
                r.getLong("ad_revision"), r.getLong("market_revision"), r.getLong("quantity_units"), r.getLong("buyer_fee_units"),
                r.getBigDecimal("fiat_amount"), r.getBigDecimal("price"), r.getObject("payment_account_id", UUID.class),
                r.getLong("payment_account_revision"), r.getTimestamp("expires_at").toInstant());
    }
}
