package com.surprising.gateway.provider.notification.controller;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.notification.model.UserDeviceToken;
import com.surprising.gateway.provider.notification.service.DeviceRegistrationService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/user/devices")
public class DeviceTokenController {

    private final AuthService authService;
    private final DeviceRegistrationService deviceService;

    public DeviceTokenController(AuthService authService, DeviceRegistrationService deviceService) {
        this.authService = authService;
        this.deviceService = deviceService;
    }

    public record RegisterDeviceRequest(
            String deviceToken,
            String platform,
            String deviceName,
            String locale
    ) {}

    public record UnregisterDeviceRequest(
            String deviceToken
    ) {}

    @PostMapping("/register")
    public Map<String, Object> register(
            @RequestHeader("Authorization") String authorization,
            @RequestBody RegisterDeviceRequest request) {
        if (request.deviceToken() == null || request.deviceToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceToken is required");
        }
        long userId = principal(authorization).userId();
        deviceService.registerDevice(userId, request.deviceToken(), request.platform(), request.deviceName(), request.locale());
        return Map.of("success", true, "message", "device registered");
    }

    @PostMapping("/unregister")
    public Map<String, Object> unregister(
            @RequestHeader("Authorization") String authorization,
            @RequestBody UnregisterDeviceRequest request) {
        if (request.deviceToken() == null || request.deviceToken().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceToken is required");
        }
        long userId = principal(authorization).userId();
        boolean unregistered = deviceService.unregisterDevice(userId, request.deviceToken());
        return Map.of("success", unregistered);
    }

    @GetMapping
    public List<UserDeviceToken> listMyDevices(@RequestHeader("Authorization") String authorization) {
        long userId = principal(authorization).userId();
        return deviceService.getUserDevices(userId);
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
