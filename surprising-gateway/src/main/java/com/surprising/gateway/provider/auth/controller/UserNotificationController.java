package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.service.UserNotificationService;
import com.surprising.gateway.provider.auth.model.NotificationView;
import com.surprising.gateway.provider.auth.service.AuthService;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/notifications")
public class UserNotificationController {

    private final AuthService authService;
    private final UserNotificationService notifications;

    public UserNotificationController(AuthService authService, UserNotificationService notifications) {
        this.authService = authService;
        this.notifications = notifications;
    }

    @GetMapping
    public List<NotificationView> list(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(value = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        try {
            return notifications.list(principal(authorization).userId(), unreadOnly, Math.max(1, Math.min(limit, 100)));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/{notificationId}/read")
    public NotificationView markRead(
            @RequestHeader("Authorization") String authorization,
            @PathVariable long notificationId) {
        try {
            long userId = principal(authorization).userId();
            if (notificationId <= 0) throw new java.util.NoSuchElementException("notification not found");
            return notifications.markRead(userId, notificationId);
        } catch (java.util.NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/read-all")
    public int markAllRead(@RequestHeader("Authorization") String authorization) {
        return notifications.markAllRead(principal(authorization).userId());
    }

    private JwtPrincipal principal(String authorization) {
        try {
            return authService.authenticateBearer(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }
}
