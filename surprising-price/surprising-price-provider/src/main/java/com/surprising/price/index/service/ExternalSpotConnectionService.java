package com.surprising.price.index.service;

import com.surprising.price.index.client.ExternalSpotWebSocketManager;
import org.springframework.stereotype.Service;

/**
 * 封装外部现货 WebSocket 连接刷新，供定时任务入口调用。
 */
@Service
public class ExternalSpotConnectionService {

    private final ExternalSpotWebSocketManager webSocketManager;

    public ExternalSpotConnectionService(ExternalSpotWebSocketManager webSocketManager) {
        this.webSocketManager = webSocketManager;
    }

    public void refreshConnections() {
        webSocketManager.refreshConnections();
    }
}
