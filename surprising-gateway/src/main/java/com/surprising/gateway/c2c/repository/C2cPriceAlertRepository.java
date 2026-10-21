package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cProfileRows.PriceAlert;
import java.math.BigDecimal;
import com.surprising.gateway.c2c.model.C2cPriceAlertTrigger;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只写c2c_price_alerts；基础广告资格由共用只读视图提供。 */
@Repository
public class C2cPriceAlertRepository {
    private final JdbcTemplate jdbc;

    public C2cPriceAlertRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 预警为分页/锁定根；关联市场只取通知字段，EXISTS共用资格视图及查看者屏蔽关系。
     * 不扩大预警行数；SKIP LOCKED将当前有界批次交给调用方事务，通知和关闭触发必须共同提交。
     */
    public List<C2cPriceAlertTrigger> lockTriggered(int limit) {
        return jdbc.query("""
                SELECT p.alert_id,p.user_id,p.market_id,p.side,p.target_price,m.asset,m.fiat
                FROM c2c_price_alerts p JOIN c2c_markets m ON m.market_id=p.market_id
                WHERE p.enabled AND EXISTS (
                    SELECT 1 FROM c2c_eligible_ads a WHERE a.market_id=p.market_id AND a.maker_user_id<>p.user_id
                        AND ((p.side='BUY' AND a.side='SELL' AND a.price<=p.target_price)
                            OR (p.side='SELL' AND a.side='BUY' AND a.price>=p.target_price))
                        AND NOT EXISTS(SELECT 1 FROM c2c_relations r WHERE r.kind='BLOCK' AND
                            ((r.user_id=p.user_id AND r.target_user_id=a.maker_user_id)
                                OR (r.target_user_id=p.user_id AND r.user_id=a.maker_user_id)))
                ) ORDER BY p.created_at,p.alert_id LIMIT ? FOR UPDATE OF p SKIP LOCKED
                """, (r, n) -> new C2cPriceAlertTrigger(r.getObject("alert_id", UUID.class), r.getLong("user_id"),
                        r.getObject("market_id", UUID.class), r.getString("asset"), r.getString("fiat"),
                        r.getString("side"), r.getBigDecimal("target_price")), limit);
    }

    public int fire(List<UUID> ids) {
        if (ids.isEmpty()) return 0;
        return jdbc.update("UPDATE c2c_price_alerts SET enabled=false,triggered_at=now(),revision=revision+1 WHERE enabled AND alert_id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?")) + ")", ids.toArray());
    }
    public List<PriceAlert> byUser(long userId) {
        return jdbc.query("SELECT * FROM c2c_price_alerts WHERE user_id=? ORDER BY created_at DESC,alert_id LIMIT 100",
                (r,n)->new PriceAlert(r.getObject("alert_id",UUID.class),r.getObject("market_id",UUID.class),r.getString("side"),
                        r.getBigDecimal("target_price"),r.getBoolean("enabled"),r.getLong("revision"),
                        r.getTimestamp("triggered_at")==null?null:r.getTimestamp("triggered_at").toInstant()),userId);
    }
    public long enabledCountExcept(long userId,UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM c2c_price_alerts WHERE user_id=? AND enabled AND (CAST(? AS uuid) IS NULL OR alert_id<>?)",
                Long.class,userId,id,id);
    }
    public void insert(UUID id,long userId,UUID market,String side,BigDecimal target,boolean enabled) {
        jdbc.update("INSERT INTO c2c_price_alerts(alert_id,user_id,market_id,side,target_price,enabled) VALUES(?,?,?,?,?,?)",
                id,userId,market,side,target,enabled);
    }
    public int replace(UUID id,long userId,long revision,UUID market,String side,BigDecimal target,boolean enabled) {
        return jdbc.update("""
                UPDATE c2c_price_alerts SET market_id=?,side=?,target_price=?,enabled=?,triggered_at=NULL,revision=revision+1
                WHERE alert_id=? AND user_id=? AND revision=?
                """,market,side,target,enabled,id,userId,revision);
    }
    public void delete(long userId,UUID id) {
        jdbc.update("DELETE FROM c2c_price_alerts WHERE user_id=? AND alert_id=?",userId,id);
    }

}
