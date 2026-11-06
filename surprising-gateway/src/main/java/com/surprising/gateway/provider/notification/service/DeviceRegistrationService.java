package com.surprising.gateway.provider.notification.service;

import com.surprising.gateway.provider.notification.model.UserDeviceToken;
import com.surprising.gateway.provider.notification.repository.UserDeviceTokenRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户设备推送 Token 注册管理服务 */
@Service
public class DeviceRegistrationService {

    private final UserDeviceTokenRepository repository;

    public DeviceRegistrationService(UserDeviceTokenRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void registerDevice(long userId, String deviceToken, String platform, String deviceName, String locale) {
        if (deviceToken == null || deviceToken.isBlank()) {
            throw new IllegalArgumentException("deviceToken cannot be empty");
        }
        repository.upsertToken(userId, deviceToken.trim(), platform, deviceName, locale, Instant.now());
    }

    @Transactional
    public boolean unregisterDevice(long userId, String deviceToken) {
        if (deviceToken == null || deviceToken.isBlank()) {
            return false;
        }
        return repository.unregisterToken(userId, deviceToken.trim(), Instant.now());
    }

    @Transactional(readOnly = true)
    public List<UserDeviceToken> getActiveDeviceTokens(long userId) {
        return repository.findActiveTokens(userId);
    }

    @Transactional(readOnly = true)
    public List<UserDeviceToken> getUserDevices(long userId) {
        return repository.findUserDevices(userId);
    }
}
