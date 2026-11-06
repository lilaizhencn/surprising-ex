package com.surprising.gateway.provider.notification;

import com.surprising.gateway.provider.auth.repository.UserNotificationRepository;
import com.surprising.gateway.provider.notification.model.NotificationPreference;
import com.surprising.gateway.provider.notification.model.UserDeviceToken;
import com.surprising.gateway.provider.notification.repository.NotificationPreferenceRepository;
import com.surprising.gateway.provider.notification.repository.PushOutboxRepository;
import com.surprising.gateway.provider.notification.repository.UserDeviceTokenRepository;
import com.surprising.gateway.provider.notification.service.DeviceRegistrationService;
import com.surprising.gateway.provider.notification.service.NotificationPreferenceService;
import com.surprising.gateway.provider.notification.service.PushDispatcher;
import com.surprising.gateway.provider.notification.service.PushSender;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PushNotificationServiceUnitTest {

    private UserNotificationRepository inAppRepository;
    private NotificationPreferenceRepository preferenceRepository;
    private UserDeviceTokenRepository deviceTokenRepository;
    private PushOutboxRepository outboxRepository;
    private PushSender pushSender;

    private NotificationPreferenceService preferenceService;
    private DeviceRegistrationService deviceService;
    private PushDispatcher pushDispatcher;

    @BeforeEach
    void setUp() {
        inAppRepository = mock(UserNotificationRepository.class);
        preferenceRepository = mock(NotificationPreferenceRepository.class);
        deviceTokenRepository = mock(UserDeviceTokenRepository.class);
        outboxRepository = mock(PushOutboxRepository.class);
        pushSender = mock(PushSender.class);

        preferenceService = new NotificationPreferenceService(preferenceRepository);
        deviceService = new DeviceRegistrationService(deviceTokenRepository);
        pushDispatcher = new PushDispatcher(inAppRepository, preferenceService, deviceService, outboxRepository, pushSender);
    }

    @Test
    @DisplayName("默认偏好设置各项通知全部启用且阈值为5%")
    void defaultPreferenceEnablesAllChannels() {
        long userId = 1001L;
        when(preferenceRepository.findByUserId(userId)).thenReturn(NotificationPreference.defaultForUser(userId));

        var pref = preferenceService.getPreference(userId);
        assertNotNull(pref);
        assertTrue(pref.marketVolatilityPush());
        assertTrue(pref.marketVolatilityInApp());
        assertEquals(new BigDecimal("5.00"), pref.marketVolatilityThresholdPct());
        assertTrue(pref.securityLoginPush());
        assertTrue(pref.tradingFillPush());
        assertTrue(pref.liquidationRiskPush());

        assertTrue(preferenceService.isChannelEnabled(userId, "MARKET", "PUSH"));
        assertTrue(preferenceService.isChannelEnabled(userId, "MARKET", "IN_APP"));
        assertTrue(preferenceService.isChannelEnabled(userId, "SECURITY", "PUSH"));
        assertTrue(preferenceService.isChannelEnabled(userId, "TRADING", "PUSH"));
    }

    @Test
    @DisplayName("当用户关闭行情Push但开启InApp时，精准拦截Push仅写入站内信")
    void whenMarketPushDisabledOnlyInAppIsDelivered() {
        long userId = 2002L;
        var customPref = new NotificationPreference(
                userId,
                false, // 关闭行情 Push
                true,  // 开启行情 InApp
                new BigDecimal("5.00"),
                true, true, true, true, true, true, true, true, true, true,
                Instant.now()
        );
        when(preferenceRepository.findByUserId(userId)).thenReturn(customPref);

        pushDispatcher.publish(
                userId,
                "MARKET",
                "BTC 价格异动",
                "BTC 突破 76,000",
                "/trade/usd-perpetual",
                "{\"symbol\":\"BTCUSDT\"}",
                "VOLATILITY:2002:BTC:1"
        );

        // 验证写入了站内信
        verify(inAppRepository, times(1)).create(
                eq(userId), eq("MARKET"), eq("BTC 价格异动"), eq("BTC 突破 76,000"),
                eq("/trade/usd-perpetual"), eq("{\"symbol\":\"BTCUSDT\"}"), any(Instant.class)
        );

        // 验证没有写入设备推送出件箱
        verifyNoInteractions(deviceTokenRepository);
        verifyNoInteractions(outboxRepository);
    }

    @Test
    @DisplayName("用户开启全部通知时，站内信与设备推送出件箱同时投递并携带深链接路由")
    void whenAllEnabledBothInAppAndPushOutboxAreEnqueued() {
        long userId = 3003L;
        when(preferenceRepository.findByUserId(userId)).thenReturn(NotificationPreference.defaultForUser(userId));

        var device = new UserDeviceToken(
                1L, userId, "fcm-token-test-123", "ANDROID", "Pixel 8 Pro", "zh-CN",
                true, Instant.now(), Instant.now(), Instant.now()
        );
        when(deviceTokenRepository.findActiveTokens(userId)).thenReturn(List.of(device));

        pushDispatcher.publish(
                userId,
                "SECURITY",
                "新设备登录提醒",
                "检测到新设备在上海登录",
                "/security",
                "{\"section\":\"devices\"}",
                "LOGIN_ALERT:3003:127.0.0.1:20261106"
        );

        // 验证写入站内信
        verify(inAppRepository, times(1)).create(
                eq(userId), eq("SECURITY"), eq("新设备登录提醒"), eq("检测到新设备在上海登录"),
                eq("/security"), eq("{\"section\":\"devices\"}"), any(Instant.class)
        );

        // 验证入队设备推送，且深链接路由与参数完整传递
        verify(outboxRepository, times(1)).enqueue(
                any(),
                eq(userId),
                eq("fcm-token-test-123"),
                eq("ANDROID"),
                eq("SECURITY"),
                eq("新设备登录提醒"),
                eq("检测到新设备在上海登录"),
                eq("/security"),
                eq("{\"section\":\"devices\"}"),
                eq("LOGIN_ALERT:3003:127.0.0.1:20261106:1"),
                any(Instant.class)
        );
    }

    @Test
    @DisplayName("设备注册管理服务校验与注销")
    void deviceRegistrationServiceOperations() {
        long userId = 4004L;
        assertThrows(IllegalArgumentException.class, () ->
                deviceService.registerDevice(userId, "   ", "IOS", "iPhone 15", "en-US"));

        deviceService.registerDevice(userId, "apns-token-abc", "IOS", "iPhone 15", "en-US");
        verify(deviceTokenRepository, times(1)).upsertToken(
                eq(userId), eq("apns-token-abc"), eq("IOS"), eq("iPhone 15"), eq("en-US"), any(Instant.class)
        );

        when(deviceTokenRepository.unregisterToken(eq(userId), eq("apns-token-abc"), any(Instant.class)))
                .thenReturn(true);
        boolean unregistered = deviceService.unregisterDevice(userId, "apns-token-abc");
        assertTrue(unregistered);
    }
}
