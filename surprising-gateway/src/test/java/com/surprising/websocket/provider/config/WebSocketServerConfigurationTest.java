package com.surprising.websocket.provider.config;

import static org.mockito.Mockito.*;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.websocket.provider.handler.ClientWebSocketHandler;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

class WebSocketServerConfigurationTest {

    @Test
    void configuredOriginsAreUsedByTheActualHandshakeRegistration() {
        register(List.of("https://app.example.com", "https://m.example.com"));
    }

    @Test
    void emptyOriginsDoNotImplicitlyAllowEveryWebsite() {
        register(List.of());
    }

    private void register(List<String> origins) {
        var properties = new WebSocketProperties();
        properties.getSecurity().setAllowedOrigins(origins);
        var handler = mock(ClientWebSocketHandler.class);
        var registry = mock(WebSocketHandlerRegistry.class);
        var registration = mock(WebSocketHandlerRegistration.class, RETURNS_SELF);
        when(registry.addHandler(handler, "/ws/v1")).thenReturn(registration);
        new WebSocketServerConfiguration(handler, properties, new GatewayProperties()).registerWebSocketHandlers(registry);
        verify(registration).setAllowedOriginPatterns(origins.toArray(String[]::new));
    }
}
