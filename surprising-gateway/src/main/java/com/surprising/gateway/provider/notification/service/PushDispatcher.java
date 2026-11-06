package com.surprising.gateway.provider.notification.service;

import com.surprising.gateway.provider.auth.repository.UserNotificationRepository;
import com.surprising.gateway.provider.notification.model.PushOutboxItem;
import com.surprising.gateway.provider.notification.model.UserDeviceToken;
import com.surprising.gateway.provider.notification.repository.PushOutboxRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 生产级统一通知与推送调度中心。
 * 负责依据用户偏好进行渠道过滤、站内通知写入、设备推送入队与异步批量派发。
 */
@Service
public class PushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

    private final UserNotificationRepository inAppRepository;
    private final NotificationPreferenceService preferenceService;
    private final DeviceRegistrationService deviceService;
    private final PushOutboxRepository outboxRepository;
    private final PushSender pushSender;

    public PushDispatcher(UserNotificationRepository inAppRepository,
                          NotificationPreferenceService preferenceService,
                          DeviceRegistrationService deviceService,
                          PushOutboxRepository outboxRepository,
                          PushSender pushSender) {
        this.inAppRepository = inAppRepository;
        this.preferenceService = preferenceService;
        this.deviceService = deviceService;
        this.outboxRepository = outboxRepository;
        this.pushSender = pushSender;
    }

    /**
     * 业务统一入口：发送一条带有深度链接路由的系统通知
     */
    @Transactional
    public void publish(long userId, String category, String title, String body,
                        String route, String routeParams, String idempotencyKey) {
        Instant now = Instant.now();

        // 1. 偏好校验与站内信写入
        boolean inAppAllowed = preferenceService.isChannelEnabled(userId, category, "IN_APP");
        if (inAppAllowed) {
            inAppRepository.create(userId, category, title, body, route, routeParams, now);
        } else {
            log.debug("In-app notification skipped for userId={}, category={} due to preferences", userId, category);
        }

        // 2. 偏好校验与系统级推送出件箱入队
        boolean pushAllowed = preferenceService.isChannelEnabled(userId, category, "PUSH");
        if (!pushAllowed) {
            log.debug("Push notification skipped for userId={}, category={} due to preferences", userId, category);
            return;
        }

        List<UserDeviceToken> activeDevices = deviceService.getActiveDeviceTokens(userId);
        if (activeDevices.isEmpty()) {
            log.debug("No active push devices found for userId={}", userId);
            return;
        }

        for (UserDeviceToken device : activeDevices) {
            String deviceKey = (idempotencyKey != null && !idempotencyKey.isBlank())
                    ? idempotencyKey + ":" + device.tokenId()
                    : UUID.randomUUID().toString();

            outboxRepository.enqueue(
                    UUID.randomUUID(),
                    userId,
                    device.deviceToken(),
                    device.platform(),
                    category,
                    title,
                    body,
                    route,
                    routeParams,
                    deviceKey,
                    now
            );
        }
    }

    /**
     * 周期性出件箱派发任务（每 3 秒调度一次）
     */
    @Scheduled(fixedDelay = 3000)
    public void dispatchPendingOutbox() {
        try {
            List<PushOutboxItem> batch = outboxRepository.claimPending(Instant.now(), 50);
            if (batch.isEmpty()) return;

            for (PushOutboxItem item : batch) {
                try {
                    pushSender.send(item);
                    outboxRepository.markSent(item.pushId(), Instant.now());
                } catch (Exception ex) {
                    log.warn("Failed to push notification to device pushId={}, attempts={}, error={}",
                            item.pushId(), item.attempts(), ex.getMessage());

                    boolean permanent = item.attempts() >= 5;
                    // 指数退避：5s, 15s, 45s, 135s...
                    long backoffSeconds = (long) Math.min(300, 5 * Math.pow(3, item.attempts() - 1));
                    Instant nextAttempt = Instant.now().plus(backoffSeconds, ChronoUnit.SECONDS);
                    outboxRepository.markFailed(item.pushId(), nextAttempt, ex.getMessage(), permanent);
                }
            }
        } catch (Exception e) {
            log.error("Error in dispatchPendingOutbox job: {}", e.getMessage(), e);
        }
    }

    /**
     * 清理 30 天前已投递成功的历史出件箱记录
     */
    @Scheduled(cron = "0 0 2 * * ?")
    public void cleanupDeliveredOutbox() {
        try {
            int deleted = outboxRepository.deleteSentBefore(Instant.now().minus(30, ChronoUnit.DAYS));
            if (deleted > 0) {
                log.info("Cleaned up {} old sent push outbox records", deleted);
            }
        } catch (Exception e) {
            log.error("Error cleaning push outbox: {}", e.getMessage());
        }
    }
}
