package com.surprising.gateway.provider.notification.service;

import com.surprising.gateway.provider.notification.model.PushOutboxItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 生产级安全模拟推送发送器。
 * 负责解析设备平台、校验 payload 格式并安全记录推送日志。
 */
@Component
public class LoggingMockPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingMockPushSender.class);

    @Override
    public void send(PushOutboxItem item) throws Exception {
        if (item.deviceToken() == null || item.deviceToken().isBlank()) {
            throw new IllegalArgumentException("invalid device token");
        }
        log.info("[PUSH NOTIFICATION DISPATCHED] platform={}, userId={}, token={}, category={}, title={}, route={}, routeParams={}",
                item.platform(), item.userId(), maskToken(item.deviceToken()), item.category(), item.title(), item.route(), item.routeParams());
    }

    private static String maskToken(String token) {
        if (token == null || token.length() <= 8) return "***";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
}
