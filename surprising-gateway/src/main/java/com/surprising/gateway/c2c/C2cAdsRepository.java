package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.model.C2cAd;
import com.surprising.gateway.c2c.model.C2cOffer;
import com.surprising.gateway.c2c.model.C2cOfferFilter;
import com.surprising.gateway.c2c.model.C2cMarket;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class C2cAdsRepository {

    private final JdbcTemplate jdbc;
    public C2cAdsRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    /**
     * 广告为稳定分页根。共用资格视图决定基础资格；档案/商家提供显示字段，订单LATERAL聚合提供统计筛选。
     * 关系仅用于当前查看者的EXISTS排除；各关联不扩大广告行数，只读当前有界页，不查询或复制Core余额。
     */
    public List<C2cOffer> eligiblePage(C2cOfferFilter filter, C2cMarket market, String makerSide, long viewerId) {
        BigDecimal amount=filter.amount();
        String direction=makerSide.equals("SELL")?"ASC":"DESC";
        String sort=switch(filter.sort()) {
            case PRICE -> "a.price "+direction+",a.ad_id";
            case COMPLETED -> "s.completed_total DESC,a.price "+direction+",a.ad_id";
        };
        return jdbc.query("""
                SELECT a.*,COALESCE(p.nickname,'商家'||right(a.maker_user_id::text,6)) AS merchant_nickname,c.tier,
                    s.completed_30d,s.ended_30d,s.completed_total,s.paid_seconds,s.released_seconds
                FROM c2c_eligible_ads a
                JOIN c2c_merchants c ON c.user_id=a.maker_user_id
                LEFT JOIN c2c_profiles p ON p.user_id=a.maker_user_id
                LEFT JOIN LATERAL (
                    SELECT count(*) FILTER (WHERE status='COMPLETED' AND completed_at>=now()-interval '30 days') AS completed_30d,
                        count(*) FILTER (WHERE status IN ('COMPLETED','CANCELED','EXPIRED') AND COALESCE(completed_at,canceled_at)>=now()-interval '30 days') AS ended_30d,
                        count(*) FILTER (WHERE status='COMPLETED') AS completed_total,
                        avg(EXTRACT(EPOCH FROM paid_at-(payment_expires_at-payment_window_seconds*interval '1 second'))) FILTER(WHERE status='COMPLETED') AS paid_seconds,
                        avg(EXTRACT(EPOCH FROM completed_at-paid_at)) FILTER(WHERE status='COMPLETED') AS released_seconds
                    FROM c2c_orders WHERE buyer_user_id=a.maker_user_id OR seller_user_id=a.maker_user_id
                ) s ON true
                WHERE a.market_id=? AND a.side=?
                    AND (CAST(? AS numeric) IS NULL OR (? BETWEEN a.min_fiat AND a.max_fiat
                        AND ROUND(a.available_units::numeric*a.price/?,?)>=?))
                    AND (CAST(? AS text) IS NULL OR ?=ANY(a.method_codes))
                    AND (CAST(? AS bigint) IS NULL OR a.maker_user_id=?)
                    AND (?=false OR c.tier='VIP')
                    AND s.completed_total>=? AND (?=0 OR (s.ended_30d>0 AND s.completed_30d*100>=s.ended_30d*?))
                    AND a.payment_window_seconds<=? AND a.maker_user_id<>?
                    AND NOT EXISTS(SELECT 1 FROM c2c_relations r WHERE r.kind='BLOCK' AND
                        ((r.user_id=? AND r.target_user_id=a.maker_user_id) OR (r.target_user_id=? AND r.user_id=a.maker_user_id)))
                ORDER BY %s LIMIT ? OFFSET ?
                """.formatted(sort), (r,n) -> new C2cOffer(read(r,n),r.getString("merchant_nickname"),r.getString("tier"),
                        r.getLong("completed_30d"),r.getLong("ended_30d"),r.getLong("completed_total"),
                        r.getBigDecimal("paid_seconds"),r.getBigDecimal("released_seconds")),
                market.id(),makerSide,amount,amount,market.assetScale(),market.fiatDecimals(),amount,
                filter.method(),filter.method(),filter.merchantId(),filter.merchantId(),filter.vipOnly(),
                filter.minCompleted(),filter.minCompletionRate(),filter.minCompletionRate(),filter.maxPaymentSeconds(),viewerId,viewerId,viewerId,
                filter.limit()+1,filter.offset());
    }

    public Long activeCount(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM c2c_ads WHERE maker_user_id=? AND status NOT IN ('CLOSED','REJECTED')", Long.class, userId);
    }

    public void pauseByMerchant(long userId) {
        jdbc.update("UPDATE c2c_ads SET status='PAUSED',revision=revision+1,updated_at=now() WHERE maker_user_id=? AND status='ACTIVE'", userId);
    }

    public void pauseUsingPaymentAccount(long userId, UUID accountId) {
        jdbc.update("""
                UPDATE c2c_ads SET status='PAUSED',revision=revision+1,updated_at=now()
                WHERE maker_user_id=? AND status='ACTIVE' AND ?=ANY(payment_account_ids)
                """, userId, accountId);
    }

    public List<C2cAd> adminPage(String status, Long userId, UUID market, int count, int offset) {
        return jdbc.query("""
                SELECT * FROM c2c_ads WHERE (CAST(? AS text) IS NULL OR status=?) AND (CAST(? AS bigint) IS NULL OR maker_user_id=?)
                    AND (CAST(? AS uuid) IS NULL OR market_id=?) ORDER BY updated_at DESC,ad_id DESC LIMIT ? OFFSET ?
                """, this::read, status, status, userId, userId, market, market, count, offset);
    }

    public C2cAd get(UUID id,boolean lock) {
        var rows=jdbc.query("SELECT * FROM c2c_ads WHERE ad_id=?"+(lock?" FOR UPDATE":""),this::read,id);
        if(rows.isEmpty()) throw new IllegalArgumentException("广告不存在");
        return rows.getFirst();
    }

    C2cAd create(UUID id,long userId,UUID market,String side,BigDecimal price,long quantity,
                    BigDecimal minFiat,BigDecimal maxFiat,List<String> methods,List<UUID> accounts,
                    int window,String terms,int minCompleted,String status) {
        jdbc.update("""
                INSERT INTO c2c_ads(ad_id,maker_user_id,market_id,side,price,total_units,available_units,min_fiat,max_fiat,
                    method_codes,payment_account_ids,payment_window_seconds,terms,min_counterparty_completed,status)
                VALUES (?,?,?,?,?,?,?,?,?,?::text[],?::uuid[],?,?,?,?)
                """,id,userId,market,side,price,quantity,quantity,minFiat,maxFiat,array(methods),array(accounts),window,
                terms,minCompleted,status);
        return get(id,false);
    }

    public List<C2cAd> mine(long userId,int limit) {
        return jdbc.query("SELECT * FROM c2c_ads WHERE maker_user_id=? ORDER BY created_at DESC,ad_id LIMIT ?",this::read,userId,limit);
    }

    /** 只读关联原AD资金意图的确认进度，筛选当前有界续办批次；本Repository只写c2c_ads，不推断Core余额。 */
    List<C2cAd> progress(int limit) {
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

    void allocate(C2cAd ad,long units) {
        int changed=jdbc.update("""
                UPDATE c2c_ads SET available_units=available_units-?,reserved_units=reserved_units+?,updated_at=now()
                WHERE ad_id=? AND status='ACTIVE' AND revision=? AND available_units>=?
                """,units,units,ad.id(),ad.revision(),units);
        if(changed!=1) throw new IllegalStateException("广告库存或报价已变更，请重新报价");
    }

    void finishOrder(C2cAd ad,long units,boolean completed,boolean restoreStock) {
        String column=completed?"completed_units":restoreStock?"available_units":"closed_units";
        int changed=jdbc.update("UPDATE c2c_ads SET reserved_units=reserved_units-?,"+column+"="+column+"+?,updated_at=now() WHERE ad_id=? AND reserved_units>=?",
                units,units,ad.id(),units);
        if(changed!=1) throw new IllegalStateException("广告订单库存对账不一致");
    }

    void closeAvailable(C2cAd ad,String status) {
        jdbc.update("""
                UPDATE c2c_ads SET available_units=0,closed_units=closed_units+?,status=?,revision=revision+1,updated_at=now()
                WHERE ad_id=?
                """,ad.availableUnits(),status,ad.id());
    }

    void edit(C2cAd ad,BigDecimal price,BigDecimal min,BigDecimal max,String terms,int minCompleted) {
        jdbc.update("""
                UPDATE c2c_ads SET price=?,min_fiat=?,max_fiat=?,terms=?,min_counterparty_completed=?,
                    revision=revision+1,updated_at=now() WHERE ad_id=?
                """,price,min,max,terms,minCompleted,ad.id());
    }

    private static String array(List<?> values) {
        return "{"+String.join(",",values.stream().map(Object::toString).toList())+"}";
    }

    C2cAd read(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        var methods=Arrays.stream((String[])r.getArray("method_codes").getArray()).toList();
        var ids=Arrays.stream((UUID[])r.getArray("payment_account_ids").getArray()).toList();
        return new C2cAd(r.getObject("ad_id",UUID.class),r.getLong("maker_user_id"),r.getObject("market_id",UUID.class),r.getString("side"),
                r.getBigDecimal("price"),r.getLong("total_units"),r.getLong("available_units"),r.getLong("reserved_units"),
                r.getLong("completed_units"),r.getLong("closed_units"),r.getBigDecimal("min_fiat"),r.getBigDecimal("max_fiat"),
                methods,ids,r.getInt("payment_window_seconds"),r.getString("terms"),r.getInt("min_counterparty_completed"),
                r.getString("status"),r.getLong("revision"));
    }
    public void pauseByMarket(UUID marketId) {
        jdbc.update("UPDATE c2c_ads SET status='PAUSED',revision=revision+1,updated_at=now() WHERE market_id=? AND status='ACTIVE'",marketId);
    }
    public void pauseByMethod(String code) {
        jdbc.update("UPDATE c2c_ads SET status='PAUSED',revision=revision+1,updated_at=now() WHERE status='ACTIVE' AND ?=ANY(method_codes)",code);
    }

}
