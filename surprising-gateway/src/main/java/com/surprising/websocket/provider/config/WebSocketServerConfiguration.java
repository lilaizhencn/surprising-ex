package com.surprising.websocket.provider.config;

import com.surprising.websocket.api.WebSocketApiPaths;
import com.surprising.websocket.provider.handler.ClientWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketServerConfiguration implements WebSocketConfigurer {

    private final ClientWebSocketHandler handler;
    private final WebSocketProperties properties;
    private final com.surprising.gateway.provider.auth.utils.ClientIpResolver addresses;

    public WebSocketServerConfiguration(ClientWebSocketHandler handler, WebSocketProperties properties,
                                        com.surprising.gateway.provider.config.GatewayProperties gateway) {
        this.handler = handler;
        this.properties = properties;
        this.addresses = new com.surprising.gateway.provider.auth.utils.ClientIpResolver(gateway);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, WebSocketApiPaths.WS_V1)
                .addInterceptors(new org.springframework.web.socket.server.HandshakeInterceptor() {
                    @Override public boolean beforeHandshake(org.springframework.http.server.ServerHttpRequest request,
                            org.springframework.http.server.ServerHttpResponse response,
                            org.springframework.web.socket.WebSocketHandler handler, java.util.Map<String, Object> attributes) {
                        if (!(request instanceof org.springframework.http.server.ServletServerHttpRequest servlet)) return false;
                        String ip = addresses.resolve(servlet.getServletRequest());
                        if (ip == null || ip.isBlank()) return false;
                        attributes.put(com.surprising.websocket.provider.session.ClientConnection.CLIENT_IP, ip);
                        return true;
                    }
                    @Override public void afterHandshake(org.springframework.http.server.ServerHttpRequest request,
                            org.springframework.http.server.ServerHttpResponse response,
                            org.springframework.web.socket.WebSocketHandler handler, Exception exception) {}
                })
                .setAllowedOriginPatterns(properties.getSecurity().getAllowedOrigins().toArray(String[]::new));
    }
}
