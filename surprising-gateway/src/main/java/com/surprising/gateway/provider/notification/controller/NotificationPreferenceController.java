package com.surprising.gateway.provider.notification.controller;

import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.AuthService;
import com.surprising.gateway.provider.notification.model.NotificationPreference;
import com.surprising.gateway.provider.notification.service.NotificationPreferenceService;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/user/notification-preferences")
public class NotificationPreferenceController {

    private final AuthService authService;
    private final NotificationPreferenceService preferenceService;

    public NotificationPreferenceController(AuthService authService, NotificationPreferenceService preferenceService) {
        this.authService = authService;
        this.preferenceService = preferenceService;
    }

    public record UpdatePreferenceRequest(
            Boolean marketVolatilityPush,
            Boolean marketVolatilityInApp,
            BigDecimal marketVolatilityThresholdPct,
            Boolean securityLoginPush,
            Boolean securityLoginEmail,
            Boolean tradingFillPush,
            Boolean tradingFillInApp,
            Boolean liquidationRiskPush,
            Boolean liquidationRiskInApp,
            Boolean assetTransferPush,
            Boolean assetTransferInApp,
            Boolean c2cOrderPush,
            Boolean c2cOrderInApp
    ) {}

    @GetMapping
    public NotificationPreference getMyPreferences(@RequestHeader("Authorization") String authorization) {
        long userId = principal(authorization).userId();
        return preferenceService.getPreference(userId);
    }

    @PutMapping
    public NotificationPreference updateMyPreferences(
            @RequestHeader("Authorization") String authorization,
            @RequestBody UpdatePreferenceRequest req) {
        long userId = principal(authorization).userId();
        NotificationPreference current = preferenceService.getPreference(userId);

        NotificationPreference updated = new NotificationPreference(
                userId,
                req.marketVolatilityPush() != null ? req.marketVolatilityPush() : current.marketVolatilityPush(),
                req.marketVolatilityInApp() != null ? req.marketVolatilityInApp() : current.marketVolatilityInApp(),
                req.marketVolatilityThresholdPct() != null ? req.marketVolatilityThresholdPct() : current.marketVolatilityThresholdPct(),
                req.securityLoginPush() != null ? req.securityLoginPush() : current.securityLoginPush(),
                req.securityLoginEmail() != null ? req.securityLoginEmail() : current.securityLoginEmail(),
                req.tradingFillPush() != null ? req.tradingFillPush() : current.tradingFillPush(),
                req.tradingFillInApp() != null ? req.tradingFillInApp() : current.tradingFillInApp(),
                req.liquidationRiskPush() != null ? req.liquidationRiskPush() : current.liquidationRiskPush(),
                req.liquidationRiskInApp() != null ? req.liquidationRiskInApp() : current.liquidationRiskInApp(),
                req.assetTransferPush() != null ? req.assetTransferPush() : current.assetTransferPush(),
                req.assetTransferInApp() != null ? req.assetTransferInApp() : current.assetTransferInApp(),
                req.c2cOrderPush() != null ? req.c2cOrderPush() : current.c2cOrderPush(),
                req.c2cOrderInApp() != null ? req.c2cOrderInApp() : current.c2cOrderInApp(),
                java.time.Instant.now()
        );

        return preferenceService.updatePreference(updated);
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
