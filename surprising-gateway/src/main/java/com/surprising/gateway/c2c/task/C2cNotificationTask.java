package com.surprising.gateway.c2c.task;

import com.surprising.gateway.c2c.service.C2cNotificationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "surprising.c2c.enabled", havingValue = "true")
public class C2cNotificationTask {
    private final C2cNotificationService notifications;

    public C2cNotificationTask(C2cNotificationService notifications) { this.notifications = notifications; }

    @Scheduled(fixedDelayString = "${surprising.c2c.notification-delay-ms:5000}", scheduler = "taskScheduler")
    public void tick() { notifications.processBatch(); }
}
