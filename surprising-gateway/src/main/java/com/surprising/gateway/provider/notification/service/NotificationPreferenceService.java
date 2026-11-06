package com.surprising.gateway.provider.notification.service;

import com.surprising.gateway.provider.notification.model.NotificationPreference;
import com.surprising.gateway.provider.notification.repository.NotificationPreferenceRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户通知偏好设置业务服务 */
@Service
public class NotificationPreferenceService {

    private final NotificationPreferenceRepository repository;

    public NotificationPreferenceService(NotificationPreferenceRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public NotificationPreference getPreference(long userId) {
        return repository.findByUserId(userId);
    }

    @Transactional
    public NotificationPreference updatePreference(NotificationPreference preference) {
        repository.upsert(preference, Instant.now());
        return repository.findByUserId(preference.userId());
    }

    /**
     * 判断指定分类在特定渠道是否开启
     */
    @Transactional(readOnly = true)
    public boolean isChannelEnabled(long userId, String category, String channel) {
        var pref = repository.findByUserId(userId);
        boolean isPush = "PUSH".equalsIgnoreCase(channel);
        boolean isInApp = "IN_APP".equalsIgnoreCase(channel);
        boolean isEmail = "EMAIL".equalsIgnoreCase(channel);

        return switch (category.toUpperCase()) {
            case "MARKET" -> isPush ? pref.marketVolatilityPush() : (isInApp && pref.marketVolatilityInApp());
            case "SECURITY" -> isPush ? pref.securityLoginPush() : (isEmail ? pref.securityLoginEmail() : true);
            case "TRADING" -> isPush ? pref.tradingFillPush() : (isInApp && pref.tradingFillInApp());
            case "FUNDING", "LIQUIDATION" -> isPush ? pref.liquidationRiskPush() : (isInApp && pref.liquidationRiskInApp());
            case "ACCOUNT" -> isPush ? pref.assetTransferPush() : (isInApp && pref.assetTransferInApp());
            case "C2C" -> isPush ? pref.c2cOrderPush() : (isInApp && pref.c2cOrderInApp());
            default -> true;
        };
    }
}
