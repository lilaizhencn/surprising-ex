package com.surprising.gateway.c2c.repository;

import com.surprising.gateway.c2c.model.C2cPriceAlertTrigger;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 只访问c2c_notifications；通知字段保持int64/标识/金额的字符串协议。 */
@Repository
public class C2cNotificationRepository {
    private final JdbcTemplate jdbc;

    public C2cNotificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insertPriceAlerts(List<C2cPriceAlertTrigger> alerts) {
        if (alerts.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO c2c_notifications(user_id,kind,entity_id,details)
                VALUES(?,'PRICE_ALERT',?,jsonb_build_object('marketId',CAST(? AS text),'asset',CAST(? AS text),
                    'fiat',CAST(? AS text),'side',CAST(? AS text),'targetPrice',CAST(? AS text)))
                """, alerts.stream().map(alert -> new Object[]{alert.userId(), alert.id(), alert.marketId().toString(),
                        alert.asset(), alert.fiat(), alert.side(), alert.targetPrice().toPlainString()}).toList());
    }
}
