package com.surprising.websocket.provider.task;

import com.surprising.websocket.provider.service.RealtimeWebSocketDispatcher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 定时线程入口；状态、业务规则及续办顺序由RealtimeWebSocketDispatcher持有。 */
@Component
@ConditionalOnProperty(name = "surprising.realtime.enabled", havingValue = "true", matchIfMissing = true)
public final class RealtimeWebSocketLeaseTask {
    private final RealtimeWebSocketDispatcher service;

    public RealtimeWebSocketLeaseTask(RealtimeWebSocketDispatcher service) {
        this.service = service;
    }

    @Scheduled(fixedDelay = 1000)
    public void heartbeat() {
        service.heartbeat();
    }
}
