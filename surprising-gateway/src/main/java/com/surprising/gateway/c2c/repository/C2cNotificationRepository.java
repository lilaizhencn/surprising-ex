package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cPriceAlertTrigger;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;
import com.surprising.gateway.c2c.model.C2cProfileRows.Notification;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问c2c_notifications；通知字段保持int64/标识/金额的字符串协议。 */
@Repository
public class C2cNotificationRepository {
    private final JdbcTemplate jdbc;

    private final ObjectMapper json;
    public C2cNotificationRepository(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}

    public void insertPriceAlerts(List<C2cPriceAlertTrigger> alerts) {
        if (alerts.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO c2c_notifications(user_id,kind,entity_id,details)
                VALUES(?,'PRICE_ALERT',?,jsonb_build_object('marketId',CAST(? AS text),'asset',CAST(? AS text),
                    'fiat',CAST(? AS text),'side',CAST(? AS text),'targetPrice',CAST(? AS text)))
                """, alerts.stream().map(alert -> new Object[]{alert.userId(), alert.id(), alert.marketId().toString(),
                        alert.asset(), alert.fiat(), alert.side(), alert.targetPrice().toPlainString()}).toList());
    }
    public List<Notification> byUser(long userId,Long before,int count) {
        return jdbc.query("""
                SELECT * FROM c2c_notifications WHERE user_id=? AND (CAST(? AS bigint) IS NULL OR notification_id<?)
                ORDER BY notification_id DESC LIMIT ?
                """,(r,n)->new Notification(r.getLong("notification_id"),r.getString("kind"),r.getObject("entity_id",UUID.class),
                        json.readTree(r.getString("details")),r.getTimestamp("read_at")!=null,r.getTimestamp("created_at").toInstant()),
                userId,before,before,count);
    }
    public long unreadCount(long userId) {
        return jdbc.queryForObject("SELECT count(*) FROM c2c_notifications WHERE user_id=? AND read_at IS NULL",Long.class,userId);
    }
    public void readThrough(long userId,long through) {
        jdbc.update("UPDATE c2c_notifications SET read_at=now() WHERE user_id=? AND read_at IS NULL AND notification_id<=?",userId,through);
    }

}
