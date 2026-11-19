package com.surprising.websocket.provider.controller;

import com.surprising.gateway.provider.auth.model.OperatingUser;
import com.surprising.websocket.provider.service.RealtimeWebSocketBridge;

import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.UserReadView;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "surprising.realtime.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/realtime")
public final class RealtimeStateController {
    private final RealtimeWebSocketBridge bridge;

    public RealtimeStateController(RealtimeWebSocketBridge bridge) {
        this.bridge = bridge;
    }

    @GetMapping("/{productLine}/state")
    public UserReadView state(
            @PathVariable ProductLine productLine,
            @OperatingUser long user) {
        if (user <= 0) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED);
        }
        try {
            return bridge.snapshot(productLine, user);
        } catch (org.springframework.dao.DataAccessException unavailable) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "READ_VIEW_UNAVAILABLE");
        }
    }
}
