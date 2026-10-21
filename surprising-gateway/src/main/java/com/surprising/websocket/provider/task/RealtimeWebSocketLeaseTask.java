package com.surprising.websocket.provider.task;

import com.surprising.websocket.provider.service.RealtimeWebSocketBridge;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 定时线程入口；状态、业务规则及续办顺序由RealtimeWebSocketBridge持有。 */
@Component
@ConditionalOnProperty(name = "surprising.realtime.enabled", havingValue = "true")
public final class RealtimeWebSocketLeaseTask {
    private final RealtimeWebSocketBridge service;

    public RealtimeWebSocketLeaseTask(RealtimeWebSocketBridge service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 5000)
    public void heartbeat() {
        service.heartbeat();
    }
}
