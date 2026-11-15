package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.NotificationView;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.auth.service.UserNotificationService;
import com.surprising.gateway.provider.auth.model.CurrentUser;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/notifications")
public class UserNotificationController {

    private final UserNotificationService notifications;

    @org.springframework.beans.factory.annotation.Autowired
    public UserNotificationController(UserNotificationService notifications) {
        this.notifications = notifications;
    }

    public UserNotificationController(AuthService authService, UserNotificationService notifications) {
        this(notifications);
    }

    @GetMapping
    public List<NotificationView> list(
            @CurrentUser long userId,
            @RequestParam(value = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        try {
            return notifications.list(userId, unreadOnly, category, Math.max(1, Math.min(limit, 100)));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(@CurrentUser long userId) {
        return Map.of("unreadCount", notifications.countUnread(userId));
    }

    @PostMapping("/{notificationId}/read")
    public NotificationView markRead(
            @CurrentUser long userId,
            @PathVariable long notificationId) {
        try {
            if (notificationId <= 0) throw new java.util.NoSuchElementException("notification not found");
            return notifications.markRead(userId, notificationId);
        } catch (java.util.NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/read-all")
    public int markAllRead(@CurrentUser long userId) {
        return notifications.markAllRead(userId);
    }
}
