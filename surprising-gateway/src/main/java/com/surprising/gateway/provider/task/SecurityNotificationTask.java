package com.surprising.gateway.provider.task;

import com.surprising.gateway.provider.service.SecurityNotificationService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SecurityNotificationTask {
    private final SecurityNotificationService notifications;

    public SecurityNotificationTask(SecurityNotificationService notifications) {
        this.notifications = notifications;
    }

    @Scheduled(fixedDelayString = "${surprising.gateway.security-notification-delay-ms:5000}")
    public void deliver() {
        notifications.deliverDue();
    }
}
