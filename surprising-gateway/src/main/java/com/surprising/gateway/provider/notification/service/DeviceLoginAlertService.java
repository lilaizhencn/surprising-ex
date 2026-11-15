package com.surprising.gateway.provider.notification.service;

import com.surprising.gateway.provider.auth.repository.GatewayLoginLogRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * 其他设备与异地登录安全检测与提醒服务
 */
@Service
public class DeviceLoginAlertService {

    private static final Logger log = LoggerFactory.getLogger(DeviceLoginAlertService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY_KEY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneId.systemDefault());

    private final GatewayLoginLogRepository loginLogRepository;
    private final PushDispatcher pushDispatcher;
    private final ObjectMapper objectMapper;

    public DeviceLoginAlertService(GatewayLoginLogRepository loginLogRepository, PushDispatcher pushDispatcher) {
        this(loginLogRepository, pushDispatcher, JSON);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DeviceLoginAlertService(
            GatewayLoginLogRepository loginLogRepository,
            PushDispatcher pushDispatcher,
            ObjectMapper objectMapper) {
        this.loginLogRepository = loginLogRepository;
        this.pushDispatcher = pushDispatcher;
        this.objectMapper = objectMapper == null ? JSON : objectMapper;
    }

    /**
     * 异步检测新登录是否属于新设备或异地 IP
     */
    @Async
    public void checkAndAlertNewDeviceLogin(long userId, String ipAddress, String userAgent) {
        if (userId <= 0 || ipAddress == null || ipAddress.isBlank()) {
            return;
        }

        try {
            Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);

            // 查询过去30天内，排除当前这条登录，是否存在相同的 IP
            int knownIpCount = loginLogRepository.countSuccessfulLoginsFromIpSince(userId, ipAddress.trim(), thirtyDaysAgo);

            // 如果该 IP 在过去30天内使用次数为 1（说明本次就是首次）或者为 0，视为新设备/异地登录
            boolean isNewLocation = knownIpCount <= 1;

            if (isNewLocation) {
                String timeStr = TIME_FMT.format(Instant.now());
                String deviceHint = simplifyUserAgent(userAgent);

                String title = "安全提醒：检测到新设备/异地登录";
                String body = String.format("您的账号于 %s 从新网络环境 (%s - %s) 登录。如非本人操作，请立即前往安全中心检查活跃会话并修改密码。",
                        timeStr, ipAddress.trim(), deviceHint);
                String route = "/security";
                String routeParams = objectMapper.writeValueAsString(Map.of("section", "devices", "ip", ipAddress.trim()));

                // 幂等防重：同一天同一 IP 只发一次
                String dayKey = DAY_KEY_FMT.format(Instant.now());
                String idempotencyKey = String.format("LOGIN_ALERT:%d:%s:%s", userId, ipAddress.trim(), dayKey);

                log.info("Triggering new device login alert for userId={}, ip={}", userId, ipAddress);
                pushDispatcher.publish(userId, "SECURITY", title, body, route, routeParams, idempotencyKey);
            }
        } catch (Exception e) {
            log.error("Failed to check new device login for userId={}: {}", userId, e.getMessage(), e);
        }
    }

    private static String simplifyUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) return "未知设备";
        String ua = userAgent.toLowerCase();
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ios")) return "iOS 移动客户端";
        if (ua.contains("android")) return "Android 移动客户端";
        if (ua.contains("macintosh") || ua.contains("mac os")) return "Mac 桌面设备";
        if (ua.contains("windows")) return "Windows PC";
        if (ua.contains("linux")) return "Linux 设备";
        return "Web 浏览器";
    }
}
