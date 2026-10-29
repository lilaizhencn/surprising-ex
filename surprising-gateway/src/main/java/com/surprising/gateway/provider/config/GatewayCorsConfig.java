package com.surprising.gateway.provider.config;

import com.surprising.websocket.provider.config.WebSocketProperties;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.web.cors.*;

@Configuration(proxyBeanMethods = false)
public class GatewayCorsConfig {
    @Bean("gatewayCorsSource")
    public CorsConfigurationSource gatewayCorsSource(GatewayProperties properties, WebSocketProperties websocket) {
        var rest = new CorsConfiguration();
        var origins = new java.util.LinkedHashSet<>(properties.getSecurity().getCorsAllowedOrigins());
        origins.addAll(properties.getSecurity().getAdminBrowserAllowedOrigins());
        rest.setAllowedOrigins(List.copyOf(origins));
        rest.setAllowedMethods(List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        rest.setAllowedHeaders(List.of("*"));
        rest.setExposedHeaders(List.of("Content-Disposition", "Retry-After", "X-Request-Id"));
        rest.setMaxAge(3600L);
        rest.setAllowCredentials(true);
        var source = new UrlBasedCorsConfigurationSource();
        for (String path : List.of("/api/**", "/fapi/**", "/dapi/**", "/eapi/**", "/sapi/**"))
            source.registerCorsConfiguration(path, rest);
        var ws = new CorsConfiguration();
        ws.setAllowedOriginPatterns(websocket.getSecurity().getAllowedOrigins());
        ws.setAllowedMethods(List.of("GET")); ws.setAllowedHeaders(List.of("*"));
        source.registerCorsConfiguration("/ws/v1", ws);
        return source;
    }

}
