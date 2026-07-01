package com.surprising.websocket.provider.config;

import com.surprising.websocket.api.WebSocketApiPaths;
import com.surprising.websocket.provider.service.ClientWebSocketHandler;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketServerConfiguration implements WebSocketConfigurer {

    private final ClientWebSocketHandler handler;
    private final WebSocketProperties properties;

    public WebSocketServerConfiguration(ClientWebSocketHandler handler, WebSocketProperties properties) {
        this.handler = handler;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, WebSocketApiPaths.WS_V1)
                .setAllowedOriginPatterns(allowedOriginPatterns());
    }

    String[] allowedOriginPatterns() {
        if (properties.getSecurity().getAllowedOrigins() == null
                || properties.getSecurity().getAllowedOrigins().isEmpty()) {
            return new String[]{"*"};
        }
        List<String> origins = properties.getSecurity().getAllowedOrigins().stream()
                .filter(origin -> origin != null && !origin.isBlank())
                .map(String::trim)
                .toList();
        if (origins.isEmpty()) {
            return new String[]{"*"};
        }
        return origins.toArray(String[]::new);
    }
}
