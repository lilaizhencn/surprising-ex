package com.surprising.gateway.c2c;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class C2cAdsRepository {
    public record Ad(UUID id,long makerUserId,UUID marketId,String side,BigDecimal price,long totalUnits,long availableUnits,
              long reservedUnits,long completedUnits,long closedUnits,BigDecimal minFiat,BigDecimal maxFiat,
              List<String> methods,List<UUID> accounts,int paymentWindowSeconds,String terms,int minCompleted,
              String status,long revision) { }
    private final JdbcTemplate jdbc;
    public C2cAdsRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public List<Ad> adminPage(String status, Long userId, UUID market, int count, int offset) {
        return jdbc.query("""
                SELECT * FROM c2c_ads WHERE (CAST(? AS text) IS NULL OR status=?) AND (CAST(? AS bigint) IS NULL OR maker_user_id=?)
                    AND (CAST(? AS uuid) IS NULL OR market_id=?) ORDER BY updated_at DESC,ad_id DESC LIMIT ? OFFSET ?
                """, this::read, status, status, userId, userId, market, market, count, offset);
    }

    Ad get(UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM c2c_ads WHERE ad_id=?"+(lock?" FOR UPDATE":""),this::read,id);
        if(rows.isEmpty()) throw new IllegalArgumentException("广告不存在");
        return rows.getFirst();
    }

    Ad create(long userId,UUID market,String side,BigDecimal price,long quantity,
                    BigDecimal minFiat,BigDecimal maxFiat,List<String> methods,List<UUID> accounts,
                    int window,String terms,int minCompleted) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO c2c_ads(ad_id,maker_user_id,market_id,side,price,total_units,available_units,min_fiat,max_fiat,
                    method_codes,payment_account_ids,payment_window_seconds,terms,min_counterparty_completed,status)
                VALUES (?,?,?,?,?,?,?,?,?,?::text[],?::uuid[],?,?,?,?)
                """,id,userId,market,side,price,quantity,quantity,minFiat,maxFiat,array(methods),array(accounts),window,
                terms,minCompleted,side.equals("SELL")?"RESERVING":"ACTIVE");
        return get(id,false);
    }

    List<Ad> mine(long userId,int limit) {
        return jdbc.query("SELECT * FROM c2c_ads WHERE maker_user_id=? ORDER BY created_at DESC,ad_id LIMIT ?",this::read,userId,limit);
    }

    List<Ad> progress(int limit) {
        return jdbc.query("""
                SELECT a.* FROM c2c_ads a WHERE
                    (a.status='RESERVING' AND EXISTS(SELECT 1 FROM c2c_fund_intents f WHERE f.owner_type='AD' AND f.owner_id=a.ad_id
                        AND f.purpose='AD_RESERVE' AND f.status IN ('APPLIED','REJECTED')))
                    OR (a.status='CLOSING' AND EXISTS(SELECT 1 FROM c2c_fund_intents f WHERE f.owner_type='AD' AND f.owner_id=a.ad_id
                        AND f.purpose='AD_REFUND' AND f.status='APPLIED'))
                ORDER BY a.updated_at,a.ad_id LIMIT ?
                """,this::read,limit);
    }

    void setStatus(UUID id,String status) {
        jdbc.update("UPDATE c2c_ads SET status=?,revision=revision+1,updated_at=now() WHERE ad_id=?",status,id);
    }

    void allocate(Ad ad,long units) {
        int changed=jdbc.update("""
                UPDATE c2c_ads SET available_units=available_units-?,reserved_units=reserved_units+?,updated_at=now()
                WHERE ad_id=? AND status='ACTIVE' AND revision=? AND available_units>=?
                """,units,units,ad.id(),ad.revision(),units);
        if(changed!=1) throw new IllegalStateException("广告库存或报价已变更，请重新报价");
    }

    void finishOrder(Ad ad,long units,boolean completed,boolean restoreStock) {
        String column=completed?"completed_units":restoreStock?"available_units":"closed_units";
        int changed=jdbc.update("UPDATE c2c_ads SET reserved_units=reserved_units-?,"+column+"="+column+"+?,updated_at=now() WHERE ad_id=? AND reserved_units>=?",
                units,units,ad.id(),units);
        if(changed!=1) throw new IllegalStateException("广告订单库存对账不一致");
    }

    void closeAvailable(Ad ad,String status) {
        jdbc.update("""
                UPDATE c2c_ads SET available_units=0,closed_units=closed_units+?,status=?,revision=revision+1,updated_at=now()
                WHERE ad_id=?
                """,ad.availableUnits(),status,ad.id());
    }

    void edit(Ad ad,BigDecimal price,BigDecimal min,BigDecimal max,String terms,int minCompleted) {
        jdbc.update("""
                UPDATE c2c_ads SET price=?,min_fiat=?,max_fiat=?,terms=?,min_counterparty_completed=?,
                    revision=revision+1,updated_at=now() WHERE ad_id=?
                """,price,min,max,terms,minCompleted,ad.id());
    }

    private static String array(List<?> values) {
        return "{"+String.join(",",values.stream().map(Object::toString).toList())+"}";
    }

    Ad read(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        var methods=Arrays.stream((String[])r.getArray("method_codes").getArray()).toList();
        var ids=Arrays.stream((UUID[])r.getArray("payment_account_ids").getArray()).toList();
        return new Ad(r.getObject("ad_id",UUID.class),r.getLong("maker_user_id"),r.getObject("market_id",UUID.class),r.getString("side"),
                r.getBigDecimal("price"),r.getLong("total_units"),r.getLong("available_units"),r.getLong("reserved_units"),
                r.getLong("completed_units"),r.getLong("closed_units"),r.getBigDecimal("min_fiat"),r.getBigDecimal("max_fiat"),
                methods,ids,r.getInt("payment_window_seconds"),r.getString("terms"),r.getInt("min_counterparty_completed"),
                r.getString("status"),r.getLong("revision"));
    }
}
