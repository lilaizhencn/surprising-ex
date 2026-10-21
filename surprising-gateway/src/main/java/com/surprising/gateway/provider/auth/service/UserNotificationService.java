package com.surprising.gateway.provider.auth.service;

import com.surprising.gateway.provider.auth.model.NotificationView;
import com.surprising.gateway.provider.auth.repository.UserNotificationRepository;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 站内通知按可信用户身份读取；已读时间由业务决定，更新及结果读取处于同一事务。 */
@Service
public class UserNotificationService {
    private final UserNotificationRepository repository;

    public UserNotificationService(UserNotificationRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<NotificationView> list(long userId, boolean unreadOnly, int limit) {
        return repository.findForUser(userId, unreadOnly, limit);
    }

    @Transactional
    public NotificationView markRead(long userId, long notificationId) {
        return repository.markRead(userId, notificationId, Instant.now())
                .orElseThrow(() -> new NoSuchElementException("notification not found"));
    }

    @Transactional
    public int markAllRead(long userId) {
        return repository.markAllRead(userId, Instant.now());
    }
}
