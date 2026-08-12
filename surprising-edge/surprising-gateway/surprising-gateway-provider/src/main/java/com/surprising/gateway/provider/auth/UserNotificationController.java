package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import java.time.Instant;
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
    private final UserNotificationRepository repository;

    public UserNotificationController(AuthService authService, UserNotificationRepository repository) {
        this.authService = authService;
        this.repository = repository;
    }

    @GetMapping
    public List<UserNotificationRepository.NotificationView> list(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(value = "unreadOnly", defaultValue = "false") boolean unreadOnly,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        try {
            return repository.findForUser(principal(authorization).userId(), unreadOnly, limit);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/{notificationId}/read")
    public UserNotificationRepository.NotificationView markRead(
            @RequestHeader("Authorization") String authorization,
            @PathVariable long notificationId) {
        try {
            return repository.markRead(principal(authorization).userId(), notificationId, Instant.now());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @PostMapping("/read-all")
    public int markAllRead(@RequestHeader("Authorization") String authorization) {
        return repository.markAllRead(principal(authorization).userId(), Instant.now());
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
