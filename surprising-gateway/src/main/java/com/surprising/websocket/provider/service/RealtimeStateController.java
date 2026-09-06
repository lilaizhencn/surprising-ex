package com.surprising.websocket.provider.service;

import com.surprising.product.api.ProductLine;
import com.surprising.realtime.api.UserReadView;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name = "surprising.realtime.enabled", havingValue = "true")
@RequestMapping("/api/v1/realtime")
public final class RealtimeStateController {
    private final RealtimeWebSocketBridge bridge;
    private final WebSocketJwtAuthenticator auth;

    public RealtimeStateController(RealtimeWebSocketBridge bridge, WebSocketJwtAuthenticator auth) {
        this.bridge = bridge;
        this.auth = auth;
    }

    @GetMapping("/{productLine}/state")
    public UserReadView state(
            @PathVariable ProductLine productLine,
            @RequestHeader("Authorization") String authorization) {
        if (!authorization.startsWith("Bearer "))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED);
        long user;
        try {
            user = auth.authenticate(authorization.substring(7));
            if (user <= 0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
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
