package com.surprising.websocket.provider.controller;

import com.surprising.websocket.provider.model.WebSocketAdminMetrics;
import com.surprising.websocket.provider.service.SubscriptionRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Exposes shared WebSocket metrics directly as a root MVC controller behind gateway security. */
@RestController
@RequestMapping("/api/v1/admin/websocket")
public class WebSocketAdminController {

    private final SubscriptionRegistry registry;

    public WebSocketAdminController(SubscriptionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/metrics")
    public WebSocketAdminMetrics metrics(
            @RequestHeader(value = "X-Admin-User-Id", required = false) String adminUserId,
            @RequestHeader(value = "X-Admin-Username", required = false) String adminUsername) {
        return registry.metrics(adminUserId, adminUsername);
    }
}
