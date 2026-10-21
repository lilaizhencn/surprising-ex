package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.model.C2cPriceAlertTrigger;
import com.surprising.gateway.c2c.repository.C2cNotificationRepository;
import com.surprising.gateway.c2c.repository.C2cPriceAlertRepository;
import com.surprising.gateway.c2c.repository.C2cQuoteRepository;
import com.surprising.gateway.c2c.repository.C2cSecurityGrantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 领取预警、关闭本次触发并写入通知；纯数据库事务，无Core或外部IO。 */
@Service
public class C2cNotificationService {
    private final C2cPriceAlertRepository alerts;
    private final C2cNotificationRepository notifications;
    private final C2cSecurityGrantRepository grants;
    private final C2cQuoteRepository quotes;

    public C2cNotificationService(C2cPriceAlertRepository alerts, C2cNotificationRepository notifications,
                                   C2cSecurityGrantRepository grants, C2cQuoteRepository quotes) {
        this.alerts = alerts;
        this.notifications = notifications;
        this.grants = grants;
        this.quotes = quotes;
    }

    @Transactional
    public int processBatch() {
        var triggered = alerts.lockTriggered(32);
        int fired = alerts.fire(triggered.stream().map(C2cPriceAlertTrigger::id).toList());
        if (fired != triggered.size()) throw new IllegalStateException("锁定预警的触发数量不一致");
        notifications.insertPriceAlerts(triggered);
        grants.deleteExpired(100);
        quotes.deleteExpiredUnreferenced(100);
        return fired;
    }
}
